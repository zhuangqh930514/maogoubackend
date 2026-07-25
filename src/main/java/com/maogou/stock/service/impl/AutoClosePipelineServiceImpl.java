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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AutoClosePipelineServiceImpl implements AutoClosePipelineService {

    private static final Logger log = LoggerFactory.getLogger(AutoClosePipelineServiceImpl.class);
    private static final int USER_PAGE_SIZE = 100;
    private static final int RECOVERY_BATCH_SIZE = 10;
    private static final Duration STALE_RUN_GRACE = Duration.ofMinutes(30);

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
            LocalDateTime now = LocalDateTime.now();
            recoverStalePipelines(now);
            List<AiPipelineRun> due = pipelineRunMapper.selectDueGlobalDailyRuns(
                    now, RECOVERY_BATCH_SIZE);
            if (due != null) {
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
            }
            retryDueUserProjectionReports(now);
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
                throw new IllegalStateException(message(run));
            }
            projectUser(config, run);
        } catch (RuntimeException exception) {
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
            log.warn("global daily research did not complete, runId={}, status={}, message={}",
                    run.id, run.status, message(run));
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
            String idempotencyKey = "SCHEDULED:USER_DAILY:" + config.userId + ":" + globalRun.tradeDate
                    + ":" + globalRun.id;
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
        } catch (RuntimeException exception) {
            log.warn("user daily projection submission failed, userId={}, globalRunId={}, error={}",
                    config.userId, globalRun.id, rootMessage(exception), exception);
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

    private void recoverStalePipelines(LocalDateTime now) {
        List<AiPipelineRun> stale = pipelineRunMapper.selectStaleRunning(
                now.minus(STALE_RUN_GRACE), now, RECOVERY_BATCH_SIZE);
        if (stale == null || stale.isEmpty()) {
            return;
        }
        for (AiPipelineRun run : stale) {
            if (run == null || run.id == null) {
                continue;
            }
            String message = "任务超过 30 分钟未续租，已自动回收";
            String detail = message + "；任务类型=" + run.pipelineType + "；运行ID=" + run.id
                    + "；最后步骤=" + nullToEmpty(run.currentStep)
                    + "；最后更新时间=" + run.updatedAt;
            if (pipelineRunMapper.recoverStaleRunning(
                    run.id, now.minus(STALE_RUN_GRACE), now, message, detail) != 1) {
                continue;
            }
            log.warn("recovered stale pipeline run, runId={}, type={}, ownerUserId={}",
                    run.id, run.pipelineType, run.ownerUserId);
            retryRecoveredGlobalDailyRun(run);
            retryRecoveredUserProjection(run);
        }
    }

    private void retryRecoveredGlobalDailyRun(AiPipelineRun run) {
        if (!"GLOBAL_DAILY_RESEARCH".equals(run.pipelineType)
                || run.tradeDate == null || run.idempotencyKey == null || run.idempotencyKey.isBlank()
                || run.inputFingerprint == null || run.inputFingerprint.isBlank()) {
            return;
        }
        try {
            dailyResearchService.run(new AiGlobalDailyResearchService.PipelineRequest(
                    run.tradeDate, run.strategyReleaseId, run.modelVersionId,
                    run.idempotencyKey, run.inputFingerprint,
                    run.startedAt == null ? LocalDateTime.now() : run.startedAt));
        } catch (RuntimeException exception) {
            log.warn("recovered global daily retry submission failed, runId={}, error={}",
                    run.id, rootMessage(exception));
        }
    }

    private void retryRecoveredUserProjection(AiPipelineRun run) {
        if (!"USER_DAILY_PROJECTION".equals(run.pipelineType)
                || run.ownerUserId == null || run.parentRunId == null) {
            return;
        }
        AiPipelineRun parent = pipelineRunMapper.selectById(run.parentRunId);
        if (parent == null || !isSuccessful(parent.status) || parent.tradeDate == null) {
            return;
        }
        try {
            operationsService.runUserProjection(run.ownerUserId, new ResearchLabPayloads.ActionRequest(
                    parent.tradeDate, null, null, parent.strategyReleaseId, parent.modelVersionId,
                    parent.id, run.ownerUserId, run.idempotencyKey));
        } catch (RuntimeException exception) {
            log.warn("recovered user projection retry submission failed, runId={}, userId={}, error={}",
                    run.id, run.ownerUserId, rootMessage(exception));
        }
    }

    private void retryDueUserProjectionReports(LocalDateTime now) {
        List<AiPipelineRun> due = pipelineRunMapper.selectDueUserProjectionRetries(now, RECOVERY_BATCH_SIZE);
        if (due == null || due.isEmpty()) {
            return;
        }
        Set<Long> enabledUsers = new HashSet<>();
        for (AiModelConfig config : enabledConfigs()) {
            if (config != null && config.userId != null) {
                enabledUsers.add(config.userId);
            }
        }
        for (AiPipelineRun run : due) {
            if (run == null || run.ownerUserId == null) {
                continue;
            }
            if (!enabledUsers.contains(run.ownerUserId)) {
                String message = "用户已关闭每日自动投研，已暂停失败报告的自动重试";
                pipelineRunMapper.pauseUserProjectionRetry(run.id, now, message,
                        message + "；运行ID=" + run.id + "；用户=" + run.ownerUserId);
                continue;
            }
            AiPipelineRun parent = run.parentRunId == null ? null : pipelineRunMapper.selectById(run.parentRunId);
            if (parent == null || !isSuccessful(parent.status) || parent.tradeDate == null) {
                continue;
            }
            try {
                operationsService.runUserProjection(run.ownerUserId, new ResearchLabPayloads.ActionRequest(
                        parent.tradeDate, null, null, parent.strategyReleaseId, parent.modelVersionId,
                        parent.id, run.ownerUserId, run.idempotencyKey));
            } catch (RuntimeException exception) {
                log.warn("daily report retry submission failed, runId={}, userId={}, error={}",
                        run.id, run.ownerUserId, rootMessage(exception));
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

    private static boolean isSuccessful(String status) {
        return "SUCCESS".equals(status) || "PARTIAL_SUCCESS".equals(status);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String message(AiPipelineRun run) {
        if (run.errorMessage != null && !run.errorMessage.isBlank()) {
            return run.errorMessage;
        }
        return switch (String.valueOf(run.status)) {
            case "WAITING_SOURCE" -> "等待完整收盘数据，系统将按持久化重试时间恢复";
            case "FAILED_RECOVERABLE" -> "全局日研究遇到可恢复故障，系统将按持久化重试时间恢复";
            case "FAILED_FINAL" -> "全局日研究已达到自动恢复上限，需要检查失败详情";
            case "FAILED" -> "全局日研究流水线执行失败";
            default -> "全局日研究流水线状态：" + run.status;
        };
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
