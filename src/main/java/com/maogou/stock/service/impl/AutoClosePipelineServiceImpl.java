package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiLearningJobLog;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AutoClosePipelineService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AutoClosePipelineServiceImpl implements AutoClosePipelineService {

    private static final Logger log = LoggerFactory.getLogger(AutoClosePipelineServiceImpl.class);
    private static final String JOB_TYPE = "AUTO_CLOSE_PIPELINE";
    private static final int DAILY_STEP_COUNT = 8;

    private final AiModelConfigMapper configMapper;
    private final AiLearningJobLogMapper jobLogMapper;
    private final TradingCalendarService tradingCalendarService;
    private final AiGlobalDailyResearchService dailyPipelineServiceV2;
    private final AiGlobalResearchPreparationService preparationService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AutoClosePipelineServiceImpl(
            AiModelConfigMapper configMapper,
            AiLearningJobLogMapper jobLogMapper,
            TradingCalendarService tradingCalendarService,
            AiGlobalDailyResearchService dailyPipelineServiceV2,
            AiGlobalResearchPreparationService preparationService
    ) {
        this.configMapper = configMapper;
        this.jobLogMapper = jobLogMapper;
        this.tradingCalendarService = tradingCalendarService;
        this.dailyPipelineServiceV2 = dailyPipelineServiceV2;
        this.preparationService = preparationService;
    }

    @Override
    public void runEnabledPipelines() {
        if (!tradingCalendarService.isTradingDay(LocalDate.now())) {
            log.info("auto close pipeline skipped, today is not an A-share trading day");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("auto close pipeline skipped, another run is still active");
            return;
        }
        try {
            List<AiModelConfig> configs = configMapper.selectList(new QueryWrapper<AiModelConfig>()
                    .eq("auto_close_pipeline_enabled", 1)
                    .eq("deleted", 0)
                    .orderByAsc("user_id"));
            for (AiModelConfig config : configs) {
                if (config.userId == null) {
                    continue;
                }
                runForConfig(config);
            }
        } finally {
            running.set(false);
        }
    }

    @Override
    public void runCurrentUserNow() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("每日收盘投研流水线正在执行，请稍后查看结果");
        }
        try {
            Long userId = AuthContext.currentUserIdOrDefault();
            AiModelConfig config = configMapper.selectOne(new QueryWrapper<AiModelConfig>()
                    .eq("user_id", userId)
                    .eq("deleted", 0)
                    .last("LIMIT 1"));
            if (config == null) {
                throw new IllegalStateException("请先完成模型配置，再执行每日收盘投研流水线");
            }
            RunOutcome outcome = runForConfig(config);
            if (!outcome.successful()) {
                throw new IllegalStateException(outcome.message());
            }
        } finally {
            running.set(false);
        }
    }

    private RunOutcome runForConfig(AiModelConfig config) {
        Long userId = config.userId;
        return AuthContext.callAs(userId, () -> {
            AiLearningJobLog job = startJob(userId);
            try {
                LocalDateTime startedAt = LocalDateTime.now();
                LocalDate tradeDate = tradingCalendarService.latestExpectedKlineDate(startedAt);
                String idempotencyKey = "GLOBAL_DAILY:" + tradeDate;
                AiGlobalResearchPreparationService.PreparedPipeline prepared = preparationService.prepare(
                        tradeDate, startedAt, idempotencyKey);
                AiGlobalDailyResearchService.PipelineResult result = dailyPipelineServiceV2.run(
                        new AiGlobalDailyResearchService.PipelineRequest(
                                tradeDate,
                                prepared.strategyReleaseId(),
                                prepared.modelVersionId(),
                                idempotencyKey,
                                prepared.inputFingerprint(),
                                startedAt));
                int failedCount = result.run().failedCount == null ? 0 : result.run().failedCount;
                int successCount = result.run().successCount == null ? 0 : result.run().successCount;
                int processedCount = result.run().processedCount == null ? DAILY_STEP_COUNT : result.run().processedCount;
                finishJob(job, result.run().status, processedCount, successCount, failedCount, result.run().errorMessage);
                updateConfigStatus(config, result.run().status, trimMessage(result.run().errorMessage == null
                        ? "自动收盘学习流水线执行完成"
                        : result.run().errorMessage), true);
                boolean successful = "SUCCESS".equals(result.run().status)
                        || "PARTIAL_SUCCESS".equals(result.run().status);
                if (successful) {
                    log.info("auto close pipeline finished, userId={}, status={}", userId, result.run().status);
                } else {
                    log.warn("auto close pipeline finished without success, userId={}, status={}, error={}",
                            userId, result.run().status, result.run().errorMessage);
                }
                return new RunOutcome(successful, result.run().status,
                        result.run().errorMessage == null ? "自动收盘学习流水线执行完成" : result.run().errorMessage);
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                boolean concurrentRun = message.contains("其他实例执行") || message.contains("其他实例");
                String status = concurrentRun ? "SKIPPED" : "FAILED";
                finishJob(job, status, DAILY_STEP_COUNT, 0, concurrentRun ? 0 : DAILY_STEP_COUNT, message);
                if (!concurrentRun) {
                    updateConfigStatus(config, "FAILED", message, true);
                }
                log.warn("auto close pipeline failed, userId={}", userId, ex);
                return new RunOutcome(false, status, message);
            }
        });
    }

    private AiLearningJobLog startJob(Long userId) {
        AiLearningJobLog job = new AiLearningJobLog();
        job.userId = userId;
        job.jobName = "每日 16:00 自动收盘学习流水线";
        job.jobType = JOB_TYPE;
        job.status = "RUNNING";
        job.startedAt = LocalDateTime.now();
        job.processedCount = 0;
        job.successCount = 0;
        job.failedCount = 0;
        job.createdAt = job.startedAt;
        try {
            jobLogMapper.insert(job);
        } catch (RuntimeException ex) {
            log.warn("auto close pipeline job log insert failed, userId={}", userId, ex);
        }
        return job;
    }

    private void finishJob(AiLearningJobLog job, String status, int processed, int success, int failed, String error) {
        if (job == null || job.id == null) {
            return;
        }
        job.status = status;
        job.finishedAt = LocalDateTime.now();
        job.processedCount = processed;
        job.successCount = success;
        job.failedCount = failed;
        job.errorMessage = error;
        jobLogMapper.updateById(job);
    }

    private void updateConfigStatus(AiModelConfig source, String status, String message, boolean finished) {
        AiModelConfig config = configMapper.selectById(source.id);
        if (config == null) {
            config = source;
        }
        LocalDateTime now = LocalDateTime.now();
        config.autoClosePipelineLastStatus = status;
        config.autoClosePipelineLastMessage = trimMessage(message);
        if ("RUNNING".equals(status)) {
            config.autoClosePipelineLastRunAt = now;
        }
        if (finished) {
            config.autoClosePipelineLastFinishedAt = now;
        }
        config.updatedAt = now;
        configMapper.updateById(config);
    }
    private static String trimMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record RunOutcome(boolean successful, String status, String message) {
    }
}
