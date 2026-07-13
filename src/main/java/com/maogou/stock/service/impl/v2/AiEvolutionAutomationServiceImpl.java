package com.maogou.stock.service.impl.v2;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiLearningJobLog;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.v2.AiEvolutionAutomationService;
import com.maogou.stock.service.v2.AiMonthlyTrainingRunner;
import com.maogou.stock.service.v2.AiWeeklyEvolutionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AiEvolutionAutomationServiceImpl implements AiEvolutionAutomationService {

    private static final Logger log = LoggerFactory.getLogger(AiEvolutionAutomationServiceImpl.class);
    private static final String WEEKLY_JOB_TYPE = "AI_V2_WEEKLY_EVOLUTION";
    private static final String MONTHLY_JOB_TYPE = "AI_V2_MONTHLY_TRAINING";

    private final AiModelConfigMapper configMapper;
    private final AiLearningJobLogMapper jobLogMapper;
    private final AiWeeklyEvolutionRunner weeklyRunner;
    private final AiMonthlyTrainingRunner monthlyRunner;
    private final AtomicBoolean weeklyRunning = new AtomicBoolean(false);
    private final AtomicBoolean monthlyRunning = new AtomicBoolean(false);

    public AiEvolutionAutomationServiceImpl(
            AiModelConfigMapper configMapper,
            AiLearningJobLogMapper jobLogMapper,
            AiWeeklyEvolutionRunner weeklyRunner,
            AiMonthlyTrainingRunner monthlyRunner
    ) {
        this.configMapper = configMapper;
        this.jobLogMapper = jobLogMapper;
        this.weeklyRunner = weeklyRunner;
        this.monthlyRunner = monthlyRunner;
    }

    @Override
    public void runWeeklyForEnabledUsers() {
        runEnabledUsers("周度进化", weeklyRunning, this::runWeeklyForUser);
    }

    @Override
    public void runMonthlyForEnabledUsers() {
        runEnabledUsers("月度训练", monthlyRunning, this::runMonthlyForUser);
    }

    @Override
    public CycleResult runWeeklyForUser(Long userId, LocalDateTime triggeredAt) {
        return runForUser(userId, triggeredAt, "每周 V2 实验、回测与 Challenger 评估",
                WEEKLY_JOB_TYPE, weeklyRunner::run);
    }

    @Override
    public CycleResult runMonthlyForUser(Long userId, LocalDateTime triggeredAt) {
        return runForUser(userId, triggeredAt, "每月 V2 数据集训练与 Challenger 生成",
                MONTHLY_JOB_TYPE, monthlyRunner::run);
    }

    private void runEnabledUsers(String cycleName, AtomicBoolean guard, UserCycle cycle) {
        if (!guard.compareAndSet(false, true)) {
            log.warn("AI V2 {}任务仍在运行，本次触发已跳过", cycleName);
            return;
        }
        try {
            List<AiModelConfig> configs = configMapper.selectList(new QueryWrapper<AiModelConfig>()
                    .eq("auto_close_pipeline_enabled", 1)
                    .eq("deleted", 0)
                    .isNotNull("user_id")
                    .orderByAsc("user_id"));
            LocalDateTime triggeredAt = LocalDateTime.now();
            for (AiModelConfig config : configs) {
                CycleResult result = cycle.run(config.userId, triggeredAt);
                if (!result.successful()) {
                    log.warn("AI V2 {}任务失败，userId={}, message={}", cycleName, config.userId, result.message());
                }
            }
        } finally {
            guard.set(false);
        }
    }

    private CycleResult runForUser(
            Long userId,
            LocalDateTime triggeredAt,
            String jobName,
            String jobType,
            UserRunner runner
    ) {
        if (userId == null || userId <= 0 || triggeredAt == null) {
            throw new IllegalArgumentException("AI V2 自动化任务缺少用户或触发时间");
        }
        return AuthContext.callAs(userId, () -> {
            AiLearningJobLog job = startJob(userId, jobName, jobType, triggeredAt);
            CycleResult result;
            try {
                result = runner.run(userId, triggeredAt);
                if (result == null) {
                    throw new IllegalStateException("AI V2 自动化运行器未返回结果");
                }
            } catch (RuntimeException exception) {
                String message = rootMessage(exception);
                result = new CycleResult("FAILED", 1, 0, 1, message);
                log.warn("{}失败，userId={}", jobName, userId, exception);
            }
            finishJob(job, result, LocalDateTime.now());
            return result;
        });
    }

    private AiLearningJobLog startJob(Long userId, String name, String type, LocalDateTime now) {
        AiLearningJobLog job = new AiLearningJobLog();
        job.userId = userId;
        job.jobName = name;
        job.jobType = type;
        job.status = "RUNNING";
        job.startedAt = now;
        job.processedCount = 0;
        job.successCount = 0;
        job.failedCount = 0;
        job.createdAt = now;
        jobLogMapper.insert(job);
        return job;
    }

    private void finishJob(AiLearningJobLog job, CycleResult result, LocalDateTime finishedAt) {
        job.status = result.status();
        job.processedCount = Math.max(0, result.processedCount());
        job.successCount = Math.max(0, result.successCount());
        job.failedCount = Math.max(0, result.failedCount());
        job.errorMessage = result.message();
        job.finishedAt = finishedAt;
        jobLogMapper.updateById(job);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface UserCycle {
        CycleResult run(Long userId, LocalDateTime triggeredAt);
    }

    @FunctionalInterface
    private interface UserRunner {
        CycleResult run(Long userId, LocalDateTime triggeredAt);
    }
}
