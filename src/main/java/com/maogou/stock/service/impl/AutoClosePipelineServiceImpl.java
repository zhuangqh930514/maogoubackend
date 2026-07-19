package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AutoClosePipelineService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import com.maogou.stock.service.research.AiResearchOperationsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AutoClosePipelineServiceImpl implements AutoClosePipelineService {

    private static final Logger log = LoggerFactory.getLogger(AutoClosePipelineServiceImpl.class);
    private static final int USER_PAGE_SIZE = 100;
    private static final int RECOVERY_BATCH_SIZE = 10;

    private final AiModelConfigMapper configMapper;
    private final TradingCalendarService tradingCalendarService;
    private final AiGlobalDailyResearchService dailyResearchService;
    private final AiGlobalResearchPreparationService preparationService;
    private final AiResearchOperationsService operationsService;
    private final AiPipelineRunMapper pipelineRunMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AutoClosePipelineServiceImpl(
            AiModelConfigMapper configMapper,
            TradingCalendarService tradingCalendarService,
            AiGlobalDailyResearchService dailyResearchService,
            AiGlobalResearchPreparationService preparationService,
            AiResearchOperationsService operationsService,
            AiPipelineRunMapper pipelineRunMapper
    ) {
        this.configMapper = configMapper;
        this.tradingCalendarService = tradingCalendarService;
        this.dailyResearchService = dailyResearchService;
        this.preparationService = preparationService;
        this.operationsService = operationsService;
        this.pipelineRunMapper = pipelineRunMapper;
    }

    @Override
    public void runEnabledPipelines() {
        LocalDate today = LocalDate.now();
        if (!tradingCalendarService.isTradingDay(today)) {
            log.info("auto close pipeline skipped, today is not an A-share trading day");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("auto close pipeline skipped, another run is still active");
            return;
        }
        try {
            LocalDateTime startedAt = LocalDateTime.now();
            LocalDate tradeDate = tradingCalendarService.latestExpectedKlineDate(startedAt);
            AiGlobalDailyResearchService.PipelineResult result = startGlobalRun(tradeDate, startedAt);
            handleGlobalResult(result, enabledConfigs());
        } catch (RuntimeException exception) {
            log.warn("scheduled global research failed: {}", rootMessage(exception), exception);
        } finally {
            running.set(false);
        }
    }

    @Override
    public void retryWaitingPipelines() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<AiPipelineRun> due = pipelineRunMapper.selectDueGlobalDailyRuns(
                    LocalDateTime.now(), RECOVERY_BATCH_SIZE);
            if (due == null || due.isEmpty()) {
                return;
            }
            for (AiPipelineRun run : due) {
                if (run == null) {
                    continue;
                }
                if (run.tradeDate == null || !tradingCalendarService.isTradingDay(run.tradeDate)) {
                    abandonInvalidWaitingRun(run);
                    continue;
                }
                try {
                    AiGlobalDailyResearchService.PipelineResult result = dailyResearchService.run(
                            new AiGlobalDailyResearchService.PipelineRequest(
                                    run.tradeDate,
                                    run.strategyReleaseId,
                                    run.modelVersionId,
                                    run.idempotencyKey,
                                    run.inputFingerprint,
                                    run.startedAt == null ? LocalDateTime.now() : run.startedAt));
                    handleGlobalResult(result, enabledConfigs());
                } catch (RuntimeException exception) {
                    log.warn("waiting global research recovery failed, runId={}, error={}",
                            run.id, rootMessage(exception), exception);
                }
            }
        } finally {
            running.set(false);
        }
    }

    @Override
    public void runCurrentUserNow() {
        Long userId = AuthContext.currentUserId().orElseThrow(() ->
                new AccessDeniedException("请先登录"));
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("每日收盘投研流水线正在执行，请稍后查看结果");
        }
        try {
            AiModelConfig config = configMapper.selectOne(new QueryWrapper<AiModelConfig>()
                    .eq("user_id", userId)
                    .eq("deleted", 0)
                    .last("LIMIT 1"));
            if (config == null) {
                throw new IllegalStateException("请先完成模型配置，再执行每日收盘投研流水线");
            }
            LocalDateTime startedAt = LocalDateTime.now();
            LocalDate tradeDate = tradingCalendarService.latestExpectedKlineDate(startedAt);
            AiGlobalDailyResearchService.PipelineResult result = startGlobalRun(tradeDate, startedAt);
            AiPipelineRun run = result.run();
            if (!isSuccessful(run.status)) {
                updateConfigStatus(config, run.status, message(run), run.finishedAt != null);
                throw new IllegalStateException(message(run));
            }
            projectUser(config, run);
        } catch (RuntimeException exception) {
            if (!isConcurrentRun(exception)) {
                AiModelConfig config = configMapper.selectOne(new QueryWrapper<AiModelConfig>()
                        .eq("user_id", userId).eq("deleted", 0).last("LIMIT 1"));
                if (config != null && !"FAILED".equals(config.autoClosePipelineLastStatus)) {
                    updateConfigStatus(config, "FAILED", rootMessage(exception), true);
                }
            }
            throw exception;
        } finally {
            running.set(false);
        }
    }

    private AiGlobalDailyResearchService.PipelineResult startGlobalRun(
            LocalDate tradeDate,
            LocalDateTime startedAt
    ) {
        String idempotencyKey = "SCHEDULED:GLOBAL_DAILY:" + tradeDate;
        AiGlobalResearchPreparationService.PreparedPipeline prepared = preparationService.prepare(
                tradeDate, startedAt, idempotencyKey);
        return dailyResearchService.run(new AiGlobalDailyResearchService.PipelineRequest(
                tradeDate,
                prepared.strategyReleaseId(),
                prepared.modelVersionId(),
                idempotencyKey,
                prepared.inputFingerprint(),
                startedAt));
    }

    private void handleGlobalResult(
            AiGlobalDailyResearchService.PipelineResult result,
            List<AiModelConfig> configs
    ) {
        if (result == null || result.run() == null) {
            throw new IllegalStateException("全局日研究流水线未返回运行记录");
        }
        AiPipelineRun run = result.run();
        if (!isSuccessful(run.status)) {
            for (AiModelConfig config : configs) {
                updateConfigStatus(config, run.status, message(run), run.finishedAt != null);
            }
            return;
        }
        for (AiModelConfig config : configs) {
            projectUser(config, run);
        }
    }

    private void projectUser(AiModelConfig config, AiPipelineRun globalRun) {
        if (config == null || config.userId == null || config.userId <= 0) {
            return;
        }
        try {
            String idempotencyKey = "SCHEDULED:USER_DAILY:" + config.userId + ":" + globalRun.tradeDate;
            ResearchLabPayloads.ActionAccepted accepted = operationsService.runUserProjection(
                    config.userId,
                    new ResearchLabPayloads.ActionRequest(
                            globalRun.tradeDate,
                            null,
                            null,
                            globalRun.strategyReleaseId,
                            globalRun.modelVersionId,
                            globalRun.id,
                            null,
                            idempotencyKey));
            boolean finished = isSuccessful(accepted.status()) || "FAILED".equals(accepted.status());
            updateConfigStatus(config, accepted.status(),
                    "用户日报投影流水线 #" + accepted.pipelineRunId() + " 已提交", finished);
        } catch (RuntimeException exception) {
            updateConfigStatus(config, "FAILED", rootMessage(exception), true);
            log.warn("user daily projection submission failed, userId={}, globalRunId={}",
                    config.userId, globalRun.id, exception);
        }
    }

    private List<AiModelConfig> enabledConfigs() {
        List<AiModelConfig> result = new ArrayList<>();
        long afterUserId = 0L;
        while (true) {
            List<AiModelConfig> page = configMapper.selectEnabledAutomationConfigsAfter(
                    afterUserId, USER_PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                return result;
            }
            long nextCursor = afterUserId;
            for (AiModelConfig config : page) {
                if (config != null && config.userId != null && config.userId > afterUserId) {
                    result.add(config);
                    nextCursor = Math.max(nextCursor, config.userId);
                }
            }
            if (nextCursor == afterUserId) {
                throw new IllegalStateException("启用用户分页查询未推进游标");
            }
            afterUserId = nextCursor;
            if (page.size() < USER_PAGE_SIZE) {
                return result;
            }
        }
    }

    private void abandonInvalidWaitingRun(AiPipelineRun run) {
        LocalDate expected = tradingCalendarService.latestExpectedKlineDate(LocalDateTime.now());
        run.status = "FAILED";
        run.currentStep = "INVALID_TRADE_DATE";
        run.nextRetryAt = null;
        run.finishedAt = LocalDateTime.now();
        run.updatedAt = run.finishedAt;
        run.errorMessage = trimMessage("非交易日全局研究流水线已停止重试，请改用最近收盘交易日 " + expected + " 重新生成");
        run.errorDetail = run.errorMessage;
        pipelineRunMapper.updateById(run);
        log.warn("abandoned invalid waiting global research run, runId={}, tradeDate={}, expectedTradeDate={}",
                run.id, run.tradeDate, expected);
    }

    private void updateConfigStatus(
            AiModelConfig config,
            String status,
            String message,
            boolean finished
    ) {
        LocalDateTime now = LocalDateTime.now();
        config.autoClosePipelineLastStatus = status;
        config.autoClosePipelineLastMessage = trimMessage(message);
        config.autoClosePipelineLastRunAt = now;
        if (finished) {
            config.autoClosePipelineLastFinishedAt = now;
        }
        config.updatedAt = now;
        configMapper.updateById(config);
    }

    private static boolean isSuccessful(String status) {
        return "SUCCESS".equals(status) || "PARTIAL_SUCCESS".equals(status);
    }

    private static String message(AiPipelineRun run) {
        if (run.errorMessage != null && !run.errorMessage.isBlank()) {
            return run.errorMessage;
        }
        return switch (String.valueOf(run.status)) {
            case "WAITING_SOURCE" -> "等待完整收盘数据，系统将按持久化重试时间恢复";
            case "FAILED" -> "全局日研究流水线执行失败";
            default -> "全局日研究流水线状态：" + run.status;
        };
    }

    private static boolean isConcurrentRun(Throwable throwable) {
        String message = rootMessage(throwable);
        return message.contains("其他实例") || message.contains("租约");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String trimMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
