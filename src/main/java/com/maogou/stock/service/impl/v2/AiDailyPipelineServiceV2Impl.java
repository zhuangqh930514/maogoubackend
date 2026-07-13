package com.maogou.stock.service.impl.v2;

import com.maogou.stock.domain.entity.v2.AiPipelineRun;
import com.maogou.stock.domain.entity.v2.AiPipelineStep;
import com.maogou.stock.mapper.v2.AiPipelineRunMapper;
import com.maogou.stock.mapper.v2.AiPipelineStepMapper;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.v2.AiDailyPipelineExecutor;
import com.maogou.stock.service.v2.AiDailyPipelineServiceV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiDailyPipelineServiceV2Impl implements AiDailyPipelineServiceV2 {

    private static final Logger log = LoggerFactory.getLogger(AiDailyPipelineServiceV2Impl.class);
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    private static final ScheduledExecutorService HEARTBEAT_EXECUTOR = Executors.newScheduledThreadPool(
            1, daemonThreadFactory());

    static final List<String> DAILY_STEPS = List.of(
            "FETCH_DATA",
            "CHECK_DATA_QUALITY",
            "BUILD_SAMPLES",
            "VERIFY_LABELS",
            "COMPUTE_FACTORS",
            "GENERATE_PREDICTIONS",
            "GENERATE_REPORTS",
            "BUILD_DAILY_INSIGHT",
            "BUILD_RESEARCH_DAILY_REPORT"
    );

    private final AiPipelineRunMapper runMapper;
    private final AiPipelineStepMapper stepMapper;
    private final AiDailyPipelineExecutor executor;
    private final AiResearchDailyReportService researchDailyReportService;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    @Autowired
    public AiDailyPipelineServiceV2Impl(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            AiDailyPipelineExecutor executor,
            AiResearchDailyReportService researchDailyReportService
    ) {
        this(runMapper, stepMapper, executor, researchDailyReportService,
                DEFAULT_LEASE_DURATION, DEFAULT_HEARTBEAT_INTERVAL);
    }

    AiDailyPipelineServiceV2Impl(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            AiDailyPipelineExecutor executor,
            AiResearchDailyReportService researchDailyReportService,
            Duration leaseDuration,
            Duration heartbeatInterval
    ) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.executor = executor;
        this.researchDailyReportService = researchDailyReportService;
        if (leaseDuration == null || heartbeatInterval == null
                || leaseDuration.isZero() || leaseDuration.isNegative()
                || heartbeatInterval.isZero() || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("流水线租约和心跳间隔必须为正，且心跳必须短于租约");
        }
        this.leaseDuration = leaseDuration;
        this.heartbeatInterval = heartbeatInterval;
    }

    @Override
    public PipelineResult run(PipelineRequest request) {
        validate(request);
        AiPipelineRun run = findOrCreateRun(request);
        assertSameRequest(run, request);
        ensureSteps(run.id);
        List<AiPipelineStep> steps = orderedDailySteps(run.id);
        if (isComplete(run.status)) {
            return new PipelineResult(run, steps);
        }

        String executionOwner = UUID.randomUUID().toString();
        LocalDateTime claimTime = LocalDateTime.now();
        LocalDateTime leaseUntil = claimTime.plus(leaseDuration);
        if (runMapper.claimExecution(run.id, executionOwner, leaseUntil, claimTime) != 1) {
            throw new IllegalStateException("每日投研流水线正在由其他实例执行，请稍后查看结果");
        }
        run.executionOwner = executionOwner;
        run.leaseUntil = leaseUntil;

        try {
            LocalDateTime now = LocalDateTime.now();
            run.status = "RUNNING";
            run.errorMessage = null;
            run.finishedAt = null;
            if (run.startedAt == null) {
                run.startedAt = request.startedAt();
            }
            run.updatedAt = now;
            updateRunFenced(run, executionOwner, now);

            AiDailyPipelineExecutor.PipelineContext context = new AiDailyPipelineExecutor.PipelineContext(
                    run.id, request.userId(), request.tradeDate(), request.dataBatchId(),
                    request.strategyReleaseId(), request.modelVersionId(), request.idempotencyKey(),
                    request.inputFingerprint(), run.startedAt,
                    () -> renewLease(run.id, executionOwner));
            for (AiPipelineStep step : steps) {
                if (canReuse(step)) {
                    continue;
                }
                renewLease(run.id, executionOwner);
                beginStep(run, step, executionOwner);
                try (LeaseHeartbeat heartbeat = startHeartbeat(run.id, executionOwner)) {
                    String projectedStatus = steps.stream()
                            .filter(item -> !"BUILD_RESEARCH_DAILY_REPORT".equals(item.stepKey))
                            .anyMatch(item -> "SUCCESS_WITH_WARNINGS".equals(item.status))
                            ? "PARTIAL_SUCCESS" : "SUCCESS";
                    String projectedMessage = warningSummary(steps);
                    AiDailyPipelineExecutor.StepOutcome outcome;
                    if ("BUILD_DAILY_INSIGHT".equals(step.stepKey)) {
                        outcome = executor.buildDailyInsight(context, projectedStatus, projectedMessage);
                    } else if ("BUILD_RESEARCH_DAILY_REPORT".equals(step.stepKey)) {
                        outcome = executor.buildResearchDailyReport(context, projectedStatus, projectedMessage);
                    } else {
                        outcome = executor.execute(step.stepKey, context);
                    }
                    heartbeat.assertHealthy();
                    renewLease(run.id, executionOwner);
                    finishStep(step, validateOutcome(step.stepKey, outcome), executionOwner);
                } catch (LeaseLostException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    renewLease(run.id, executionOwner);
                    failStep(run, step, context, exception, executionOwner);
                    return new PipelineResult(run, orderedDailySteps(run.id));
                }
            }

            renewLease(run.id, executionOwner);
            steps = orderedDailySteps(run.id);
            updateCounts(run, steps);
            run.status = steps.stream().anyMatch(item -> "SUCCESS_WITH_WARNINGS".equals(item.status))
                    ? "PARTIAL_SUCCESS" : "SUCCESS";
            run.currentStep = DAILY_STEPS.get(DAILY_STEPS.size() - 1);
            run.errorMessage = warningSummary(steps);
            run.finishedAt = LocalDateTime.now();
            run.updatedAt = run.finishedAt;
            updateRunFenced(run, executionOwner, run.updatedAt);
            return new PipelineResult(run, steps);
        } finally {
            try {
                runMapper.releaseExecution(run.id, executionOwner, LocalDateTime.now());
            } catch (RuntimeException releaseFailure) {
                log.warn("pipeline lease release failed, runId={}, owner={}", run.id, executionOwner, releaseFailure);
            }
        }
    }

    private AiPipelineRun findOrCreateRun(PipelineRequest request) {
        LocalDateTime now = LocalDateTime.now();
        AiPipelineRun expected = new AiPipelineRun();
        expected.userId = request.userId();
        expected.dataBatchId = request.dataBatchId();
        expected.strategyReleaseId = request.strategyReleaseId();
        expected.modelVersionId = request.modelVersionId();
        expected.tradeDate = request.tradeDate();
        expected.pipelineType = "DAILY_CLOSE";
        expected.idempotencyKey = request.idempotencyKey();
        expected.inputFingerprint = request.inputFingerprint();
        expected.status = "PENDING";
        expected.processedCount = 0;
        expected.successCount = 0;
        expected.failedCount = 0;
        expected.startedAt = request.startedAt();
        expected.createdAt = now;
        expected.updatedAt = now;
        runMapper.insertIgnore(expected);
        AiPipelineRun stored = runMapper.selectByIdempotencyForUpdate(
                request.userId(), request.idempotencyKey());
        if (stored == null || stored.id == null) {
            throw new IllegalStateException("日流水线创建后未读取到运行记录");
        }
        return stored;
    }

    private void ensureSteps(Long runId) {
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < DAILY_STEPS.size(); index++) {
            AiPipelineStep step = new AiPipelineStep();
            step.pipelineRunId = runId;
            step.stepKey = DAILY_STEPS.get(index);
            step.stepOrder = index + 1;
            step.status = "PENDING";
            step.retryCount = 0;
            step.inputCount = 0;
            step.outputCount = 0;
            step.createdAt = now;
            step.updatedAt = now;
            stepMapper.insertIgnore(step);
        }
    }

    private List<AiPipelineStep> orderedDailySteps(Long runId) {
        List<AiPipelineStep> stored = stepMapper.selectByRunIdForUpdate(runId);
        Map<String, AiPipelineStep> byKey = new LinkedHashMap<>();
        if (stored != null) {
            for (AiPipelineStep step : stored) {
                if (DAILY_STEPS.contains(step.stepKey)) {
                    byKey.put(step.stepKey, step);
                }
            }
        }
        List<AiPipelineStep> result = new ArrayList<>(DAILY_STEPS.size());
        for (String key : DAILY_STEPS) {
            AiPipelineStep step = byKey.get(key);
            if (step == null || step.id == null) {
                throw new IllegalStateException("日流水线缺少步骤：" + key);
            }
            result.add(step);
        }
        return result;
    }

    private void beginStep(AiPipelineRun run, AiPipelineStep step, String executionOwner) {
        LocalDateTime now = LocalDateTime.now();
        if ("FAILED".equals(step.status) || "RUNNING".equals(step.status)) {
            step.retryCount = value(step.retryCount) + 1;
        }
        step.status = "RUNNING";
        step.errorMessage = null;
        step.startedAt = now;
        step.finishedAt = null;
        step.updatedAt = now;
        updateStepFenced(step, executionOwner, now);

        run.currentStep = step.stepKey;
        run.updatedAt = now;
        updateRunFenced(run, executionOwner, now);
    }

    private void finishStep(
            AiPipelineStep step,
            AiDailyPipelineExecutor.StepOutcome outcome,
            String executionOwner
    ) {
        step.inputCount = outcome.processedCount();
        step.outputCount = outcome.successCount();
        step.checkpointJson = outcome.checkpointJson();
        step.outputFingerprint = outcome.outputFingerprint();
        step.errorMessage = outcome.errors().isEmpty() ? null : String.join("；", outcome.errors());
        step.status = outcome.failedCount() > 0 || !outcome.errors().isEmpty()
                ? "SUCCESS_WITH_WARNINGS" : "SUCCESS";
        step.finishedAt = LocalDateTime.now();
        step.updatedAt = step.finishedAt;
        updateStepFenced(step, executionOwner, step.finishedAt);
    }

    private void failStep(
            AiPipelineRun run,
            AiPipelineStep step,
            AiDailyPipelineExecutor.PipelineContext context,
            RuntimeException exception,
            String executionOwner
    ) {
        String message = rootMessage(exception);
        LocalDateTime now = LocalDateTime.now();
        step.status = "FAILED";
        step.errorMessage = message;
        step.finishedAt = now;
        step.updatedAt = now;
        updateStepFenced(step, executionOwner, now);

        List<AiPipelineStep> steps = orderedDailySteps(run.id);
        updateCounts(run, steps);
        run.status = "FAILED";
        run.currentStep = step.stepKey;
        run.errorMessage = message;
        run.finishedAt = now;
        run.updatedAt = now;
        updateRunFenced(run, executionOwner, now);
        if (!"BUILD_RESEARCH_DAILY_REPORT".equals(step.stepKey)) {
            try {
                renewLease(run.id, executionOwner);
                generateDailyReport(run, new PipelineRequest(
                        context.userId(),
                        context.tradeDate(),
                        context.dataBatchId(),
                        context.strategyReleaseId(),
                        context.modelVersionId(),
                        context.idempotencyKey(),
                        context.inputFingerprint(),
                        context.startedAt()), step.stepKey, message);
            } catch (RuntimeException ignored) {
                // The original pipeline failure remains authoritative when failure-report generation also fails.
            }
        }
        try {
            renewLease(run.id, executionOwner);
            executor.onPipelineFailure(context, step.stepKey, message);
        } catch (RuntimeException ignored) {
            // Failure reporting is best-effort and must not hide the original failure.
        }
    }

    private void renewLease(Long runId, String executionOwner) {
        LocalDateTime now = LocalDateTime.now();
        if (runMapper.renewExecution(runId, executionOwner, now.plus(leaseDuration), now) != 1) {
            throw new LeaseLostException("每日投研流水线执行租约已丢失，停止写入本次结果");
        }
    }

    private void updateRunFenced(AiPipelineRun run, String executionOwner, LocalDateTime now) {
        if (runMapper.updateStateFenced(run, executionOwner, now) != 1) {
            throw new LeaseLostException("每日投研流水线执行租约已丢失，拒绝覆盖运行状态");
        }
    }

    private void updateStepFenced(AiPipelineStep step, String executionOwner, LocalDateTime now) {
        if (stepMapper.updateStateFenced(step, executionOwner, now) != 1) {
            throw new LeaseLostException("每日投研流水线执行租约已丢失，拒绝覆盖步骤状态：" + step.stepKey);
        }
    }

    private LeaseHeartbeat startHeartbeat(Long runId, String executionOwner) {
        return new LeaseHeartbeat(runId, executionOwner);
    }

    private static final class LeaseLostException extends IllegalStateException {
        private LeaseLostException(String message) {
            super(message);
        }
    }

    private final class LeaseHeartbeat implements AutoCloseable {
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final ScheduledFuture<?> future;

        private LeaseHeartbeat(Long runId, String executionOwner) {
            long intervalMillis = Math.max(1L, heartbeatInterval.toMillis());
            this.future = HEARTBEAT_EXECUTOR.scheduleAtFixedRate(() -> {
                if (failure.get() != null) {
                    return;
                }
                try {
                    renewLease(runId, executionOwner);
                } catch (RuntimeException exception) {
                    failure.compareAndSet(null, exception);
                }
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        private void assertHealthy() {
            RuntimeException exception = failure.get();
            if (exception != null) {
                throw exception;
            }
        }

        @Override
        public void close() {
            future.cancel(false);
            assertHealthy();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "maogou-pipeline-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static AiDailyPipelineExecutor.StepOutcome validateOutcome(
            String stepKey,
            AiDailyPipelineExecutor.StepOutcome outcome
    ) {
        if (outcome == null) {
            throw new IllegalStateException("步骤未返回执行结果：" + stepKey);
        }
        if (outcome.processedCount() < 0 || outcome.successCount() < 0 || outcome.failedCount() < 0
                || outcome.successCount() + outcome.failedCount() > outcome.processedCount()) {
            throw new IllegalStateException("步骤计数无效：" + stepKey);
        }
        if (outcome.outputFingerprint() == null || outcome.outputFingerprint().isBlank()) {
            throw new IllegalStateException("步骤缺少输出指纹：" + stepKey);
        }
        return outcome;
    }

    private static void updateCounts(AiPipelineRun run, List<AiPipelineStep> steps) {
        run.processedCount = steps.stream().mapToInt(item -> value(item.inputCount)).sum();
        run.successCount = steps.stream().mapToInt(item -> value(item.outputCount)).sum();
        run.failedCount = steps.stream().mapToInt(item -> {
            if ("FAILED".equals(item.status)) {
                return Math.max(1, value(item.inputCount) - value(item.outputCount));
            }
            return Math.max(0, value(item.inputCount) - value(item.outputCount));
        }).sum();
    }

    private static String warningSummary(List<AiPipelineStep> steps) {
        return steps.stream()
                .filter(item -> "SUCCESS_WITH_WARNINGS".equals(item.status))
                .map(item -> item.stepKey + "：" + Objects.requireNonNullElse(item.errorMessage, "部分失败"))
                .reduce((left, right) -> left + "；" + right)
                .orElse(null);
    }

    private static boolean canReuse(AiPipelineStep step) {
        return ("SUCCESS".equals(step.status) || "SUCCESS_WITH_WARNINGS".equals(step.status))
                && step.outputFingerprint != null && !step.outputFingerprint.isBlank();
    }

    private static boolean isComplete(String status) {
        return "SUCCESS".equals(status) || "PARTIAL_SUCCESS".equals(status);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void assertSameRequest(AiPipelineRun run, PipelineRequest request) {
        if (!Objects.equals(run.userId, request.userId())
                || !Objects.equals(run.tradeDate, request.tradeDate())
                || !Objects.equals(run.dataBatchId, request.dataBatchId())
                || !Objects.equals(run.strategyReleaseId, request.strategyReleaseId())
                || !Objects.equals(run.modelVersionId, request.modelVersionId())
                || !Objects.equals(run.inputFingerprint, request.inputFingerprint())
                || !"DAILY_CLOSE".equals(run.pipelineType)) {
            throw new IllegalStateException("幂等键已被不同的日流水线输入占用");
        }
    }

    private static void validate(PipelineRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.tradeDate() == null || request.dataBatchId() == null || request.dataBatchId() <= 0
                || request.strategyReleaseId() == null || request.strategyReleaseId() <= 0
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.inputFingerprint() == null || request.inputFingerprint().isBlank()
                || request.startedAt() == null) {
            throw new IllegalArgumentException("日流水线缺少用户、交易日、数据批次、策略或输入指纹");
        }
    }

    private void generateDailyReport(
            AiPipelineRun run,
            PipelineRequest request,
            String failedStep,
            String pipelineMessage
    ) {
        researchDailyReportService.generate(new AiResearchDailyReportService.GenerationRequest(
                request.userId(),
                request.tradeDate(),
                run.id,
                request.strategyReleaseId(),
                request.modelVersionId(),
                reportIdempotencyKey(request, run.status, failedStep),
                run.status,
                failedStep,
                pipelineMessage,
                LocalDateTime.now()));
    }

    private static String reportIdempotencyKey(PipelineRequest request, String status, String failedStep) {
        StringBuilder builder = new StringBuilder("REPORT:")
                .append(request.idempotencyKey())
                .append(':')
                .append(status == null ? "UNKNOWN" : status);
        if (failedStep != null && !failedStep.isBlank()) {
            builder.append(':').append(failedStep);
        }
        return builder.toString();
    }
}
