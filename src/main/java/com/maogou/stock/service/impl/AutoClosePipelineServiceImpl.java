package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiLearningJobLog;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.AiDailyInsightService;
import com.maogou.stock.service.AiEvolutionService;
import com.maogou.stock.service.AiLearningService;
import com.maogou.stock.service.AutoClosePipelineService;
import com.maogou.stock.service.TradingCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AutoClosePipelineServiceImpl implements AutoClosePipelineService {

    private static final Logger log = LoggerFactory.getLogger(AutoClosePipelineServiceImpl.class);
    private static final String JOB_TYPE = "AUTO_CLOSE_PIPELINE";
    private static final List<PipelineStep> CLOSE_STEPS = List.of(
            new PipelineStep("BUILD_SAMPLES", "固化学习样本"),
            new PipelineStep("ANALYZE_WATCHLIST", "自选股 AI 分析"),
            new PipelineStep("VERIFY_LABELS", "T+N 复盘打标"),
            new PipelineStep("VERIFY_REVIEWS", "复盘验证"),
            new PipelineStep("REFRESH_FACTORS", "刷新因子权重"),
            new PipelineStep("RANK_UNIVERSE", "生成 Top K 候选"),
            new PipelineStep("RUN_EXPERIMENT", "运行策略实验"),
            new PipelineStep("RUN_BACKTEST", "Top K 回测验证"),
            new PipelineStep("RUN_MODEL_EVAL", "模型输出评测"),
            new PipelineStep("BUILD_DAILY_INSIGHT", "生成每日投研结果")
    );

    private final AiModelConfigMapper configMapper;
    private final AiLearningJobLogMapper jobLogMapper;
    private final AiLearningService aiLearningService;
    private final AiAnalysisService aiAnalysisService;
    private final AiDailyInsightService dailyInsightService;
    private final AiEvolutionService aiEvolutionService;
    private final TradingCalendarService tradingCalendarService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AutoClosePipelineServiceImpl(
            AiModelConfigMapper configMapper,
            AiLearningJobLogMapper jobLogMapper,
            AiLearningService aiLearningService,
            AiAnalysisService aiAnalysisService,
            AiDailyInsightService dailyInsightService,
            AiEvolutionService aiEvolutionService,
            TradingCalendarService tradingCalendarService
    ) {
        this.configMapper = configMapper;
        this.jobLogMapper = jobLogMapper;
        this.aiLearningService = aiLearningService;
        this.aiAnalysisService = aiAnalysisService;
        this.dailyInsightService = dailyInsightService;
        this.aiEvolutionService = aiEvolutionService;
        this.tradingCalendarService = tradingCalendarService;
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

    private void runForConfig(AiModelConfig config) {
        Long userId = config.userId;
        AuthContext.runAs(userId, () -> {
            AiLearningJobLog job = startJob(userId);
            int successCount = 0;
            updateConfigStatus(config, "RUNNING", "自动收盘学习流水线开始执行", false);
            try {
                for (PipelineStep step : CLOSE_STEPS) {
                    log.info("auto close pipeline step started, userId={}, step={}", userId, step.key());
                    runStep(step);
                    successCount++;
                }
                finishJob(job, "SUCCESS", CLOSE_STEPS.size(), successCount, 0, null);
                updateConfigStatus(config, "SUCCESS", "自动收盘学习流水线执行完成", true);
                log.info("auto close pipeline finished, userId={}", userId);
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                finishJob(job, "FAILED", CLOSE_STEPS.size(), successCount, CLOSE_STEPS.size() - successCount, message);
                updateConfigStatus(config, "FAILED", message, true);
                log.warn("auto close pipeline failed, userId={}, successCount={}", userId, successCount, ex);
            }
        });
    }

    private void runStep(PipelineStep step) {
        switch (step.key()) {
            case "BUILD_SAMPLES" -> aiLearningService.buildWatchlistSamples("WATCHLIST", "AFTER_CLOSE");
            case "ANALYZE_WATCHLIST" -> aiAnalysisService.analyzeWatchlist(null);
            case "VERIFY_LABELS" -> aiLearningService.verifyLabels();
            case "VERIFY_REVIEWS" -> aiEvolutionService.verifyReviews();
            case "REFRESH_FACTORS" -> aiEvolutionService.refreshFactors();
            case "RANK_UNIVERSE" -> aiLearningService.rankUniverse("WATCHLIST", 3, 10);
            case "RUN_EXPERIMENT" -> aiLearningService.runExperiment("自动收盘策略实验 " + nowText(), "WATCHLIST");
            case "RUN_BACKTEST" -> aiLearningService.runBacktest("自动收盘 Top K 回测 " + nowText(), "WATCHLIST", 3, 5);
            case "RUN_MODEL_EVAL" -> aiLearningService.runModelEval("REPORT_JSON", 30);
            case "BUILD_DAILY_INSIGHT" -> dailyInsightService.rebuildForCurrentUser("AUTO_CLOSE", "自动收盘流水线已生成每日 AI 投研结果");
            default -> throw new IllegalArgumentException("未知自动流水线步骤：" + step.key());
        }
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

    private static String nowText() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String trimMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record PipelineStep(String key, String title) {
    }
}
