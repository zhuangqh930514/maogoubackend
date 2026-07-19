package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
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
public class AiGlobalDailyResearchServiceImpl implements AiGlobalDailyResearchService {

    private static final Logger log = LoggerFactory.getLogger(AiGlobalDailyResearchServiceImpl.class);
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    private static final ScheduledExecutorService HEARTBEAT_EXECUTOR = Executors.newScheduledThreadPool(
            1, daemonThreadFactory());

    static final List<String> DAILY_STEPS = List.of(
            "SNAPSHOT_UNIVERSE",
            "FETCH_SOURCE_DATA",
            "WAIT_DATA_READY",
            "BUILD_SAMPLES",
            "MATURE_SAMPLE_LABELS",
            "COMPUTE_FACTORS",
            "GENERATE_PREDICTIONS",
            "EVALUATE_PREDICTIONS"
    );

    private final AiPipelineRunMapper runMapper;
    private final AiPipelineStepMapper stepMapper;
    private final AiGlobalDailyResearchExecutor executor;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    @Autowired
    public AiGlobalDailyResearchServiceImpl(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            AiGlobalDailyResearchExecutor executor
    ) {
        this(runMapper, stepMapper, executor, DEFAULT_LEASE_DURATION, DEFAULT_HEARTBEAT_INTERVAL);
    }

    AiGlobalDailyResearchServiceImpl(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            AiGlobalDailyResearchExecutor executor,
            Duration leaseDuration,
            Duration heartbeatInterval
    ) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.executor = executor;
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
        List<AiPipelineStep> steps = orderedSteps(run.id);
        if (isComplete(run.status)) {
            return new PipelineResult(run, steps);
        }

        String owner = UUID.randomUUID().toString();
        LocalDateTime claimTime = LocalDateTime.now();
        if (runMapper.claimExecution(run.id, owner, claimTime.plus(leaseDuration), claimTime) != 1) {
            throw new IllegalStateException("全局日研究流水线正在由其他实例执行，请稍后查看结果");
        }
        run.executionOwner = owner;
        run.leaseUntil = claimTime.plus(leaseDuration);

        try {
            if ("FAILED".equals(run.status) || "WAITING_SOURCE".equals(run.status)) {
                run.retryCount = value(run.retryCount) + 1;
            }
            run.status = "RUNNING";
            run.nextRetryAt = null;
            run.errorMessage = null;
            run.errorDetail = null;
            run.finishedAt = null;
            run.startedAt = run.startedAt == null ? request.startedAt() : run.startedAt;
            run.updatedAt = LocalDateTime.now();
            updateRunFenced(run, owner, run.updatedAt);

            for (AiPipelineStep step : steps) {
                if (canReuse(step, steps)) {
                    continue;
                }
                renewLease(run.id, owner);
                beginStep(run, step, owner);
                AiGlobalDailyResearchExecutor.PipelineContext context = context(run, request, steps, owner);
                try (LeaseHeartbeat heartbeat = startHeartbeat(run.id, owner)) {
                    AiGlobalDailyResearchExecutor.StepOutcome outcome = validateOutcome(
                            step.stepKey, executor.execute(step.stepKey, context));
                    heartbeat.assertHealthy();
                    renewLease(run.id, owner);
                    if (outcome.dataBatchId() != null) {
                        if (run.dataBatchId != null && !Objects.equals(run.dataBatchId, outcome.dataBatchId())
                                && !"FETCH_SOURCE_DATA".equals(step.stepKey)) {
                            throw new IllegalStateException("恢复流水线时数据批次发生变化");
                        }
                        run.dataBatchId = outcome.dataBatchId();
                    }
                    if ("WAITING_SOURCE".equals(outcome.status())) {
                        finishWaiting(run, step, outcome, owner);
                        return new PipelineResult(run, orderedSteps(run.id));
                    }
                    finishStep(step, outcome, owner);
                } catch (LeaseLostException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    renewLease(run.id, owner);
                    failStep(run, step, exception, owner);
                    return new PipelineResult(run, orderedSteps(run.id));
                }
            }

            renewLease(run.id, owner);
            steps = orderedSteps(run.id);
            updateCounts(run, steps);
            run.status = steps.stream().anyMatch(step -> "SUCCESS_WITH_WARNINGS".equals(step.status))
                    ? "PARTIAL_SUCCESS" : "SUCCESS";
            run.currentStep = DAILY_STEPS.get(DAILY_STEPS.size() - 1);
            run.nextRetryAt = null;
            run.errorMessage = warningSummary(steps);
            run.errorDetail = warningDetail(steps);
            run.finishedAt = LocalDateTime.now();
            run.updatedAt = run.finishedAt;
            updateRunFenced(run, owner, run.updatedAt);
            return new PipelineResult(run, steps);
        } finally {
            try {
                runMapper.releaseExecution(run.id, owner, LocalDateTime.now());
            } catch (RuntimeException releaseFailure) {
                log.warn("global research lease release failed, runId={}, owner={}", run.id, owner, releaseFailure);
            }
        }
    }

    private AiPipelineRun findOrCreateRun(PipelineRequest request) {
        LocalDateTime now = LocalDateTime.now();
        AiPipelineRun expected = new AiPipelineRun();
        expected.scopeType = "GLOBAL";
        expected.ownerUserId = null;
        expected.parentRunId = null;
        expected.strategyReleaseId = request.strategyReleaseId();
        expected.modelVersionId = request.modelVersionId();
        expected.tradeDate = request.tradeDate();
        expected.pipelineType = "GLOBAL_DAILY_RESEARCH";
        expected.idempotencyKey = request.idempotencyKey();
        expected.inputFingerprint = request.inputFingerprint();
        expected.status = "PENDING";
        expected.retryCount = 0;
        expected.processedCount = 0;
        expected.successCount = 0;
        expected.failedCount = 0;
        expected.startedAt = request.startedAt();
        expected.createdAt = now;
        expected.updatedAt = now;
        runMapper.insertIgnore(expected);
        AiPipelineRun stored = runMapper.selectByIdempotencyForUpdate(request.idempotencyKey());
        if (stored == null || stored.id == null) {
            throw new IllegalStateException("全局日研究流水线创建后未读取到运行记录");
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

    private List<AiPipelineStep> orderedSteps(Long runId) {
        List<AiPipelineStep> stored = stepMapper.selectByRunIdForUpdate(runId);
        Map<String, AiPipelineStep> byKey = new LinkedHashMap<>();
        if (stored != null) {
            stored.stream().filter(step -> DAILY_STEPS.contains(step.stepKey))
                    .forEach(step -> byKey.put(step.stepKey, step));
        }
        List<AiPipelineStep> ordered = new ArrayList<>(DAILY_STEPS.size());
        for (String key : DAILY_STEPS) {
            AiPipelineStep step = byKey.get(key);
            if (step == null || step.id == null) {
                throw new IllegalStateException("全局日研究流水线缺少步骤：" + key);
            }
            ordered.add(step);
        }
        return ordered;
    }

    private AiGlobalDailyResearchExecutor.PipelineContext context(
            AiPipelineRun run,
            PipelineRequest request,
            List<AiPipelineStep> steps,
            String owner
    ) {
        Map<String, String> checkpoints = new LinkedHashMap<>();
        for (AiPipelineStep step : steps) {
            if (step.checkpointJson != null && !step.checkpointJson.isBlank()) {
                checkpoints.put(step.stepKey, step.checkpointJson);
            }
        }
        return new AiGlobalDailyResearchExecutor.PipelineContext(
                run.id, request.tradeDate(), request.strategyReleaseId(), request.modelVersionId(),
                request.idempotencyKey(), request.inputFingerprint(), run.startedAt,
                value(run.retryCount), checkpoints, () -> renewLease(run.id, owner));
    }

    private void beginStep(AiPipelineRun run, AiPipelineStep step, String owner) {
        LocalDateTime now = LocalDateTime.now();
        if ("FAILED".equals(step.status) || "WAITING_SOURCE".equals(step.status)
                || "RUNNING".equals(step.status)) {
            step.retryCount = value(step.retryCount) + 1;
        }
        step.status = "RUNNING";
        step.nextRetryAt = null;
        step.leaseUntil = now.plus(leaseDuration);
        step.errorMessage = null;
        step.errorDetail = null;
        step.startedAt = now;
        step.finishedAt = null;
        step.updatedAt = now;
        updateStepFenced(step, owner, now);

        run.currentStep = step.stepKey;
        run.updatedAt = now;
        updateRunFenced(run, owner, now);
    }

    private void finishStep(
            AiPipelineStep step,
            AiGlobalDailyResearchExecutor.StepOutcome outcome,
            String owner
    ) {
        step.inputCount = outcome.processedCount();
        step.outputCount = outcome.successCount();
        step.checkpointJson = outcome.checkpointJson();
        step.outputFingerprint = outcome.outputFingerprint();
        step.errorMessage = PipelineMessageFormatter.summary(outcome.errors());
        step.errorDetail = PipelineMessageFormatter.detail(outcome.errors());
        step.status = outcome.failedCount() > 0 || !outcome.errors().isEmpty()
                || "SUCCESS_WITH_WARNINGS".equals(outcome.status())
                ? "SUCCESS_WITH_WARNINGS" : "SUCCESS";
        step.nextRetryAt = null;
        step.leaseUntil = null;
        step.finishedAt = LocalDateTime.now();
        step.updatedAt = step.finishedAt;
        updateStepFenced(step, owner, step.finishedAt);
    }

    private void finishWaiting(
            AiPipelineRun run,
            AiPipelineStep step,
            AiGlobalDailyResearchExecutor.StepOutcome outcome,
            String owner
    ) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retryAt = outcome.nextRetryAt() == null ? now.plusMinutes(10) : outcome.nextRetryAt();
        step.status = "WAITING_SOURCE";
        step.inputCount = outcome.processedCount();
        step.outputCount = outcome.successCount();
        step.checkpointJson = outcome.checkpointJson();
        step.outputFingerprint = outcome.outputFingerprint();
        step.errorMessage = outcome.errors().isEmpty()
                ? "等待完整收盘数据" : PipelineMessageFormatter.summary(outcome.errors());
        step.errorDetail = outcome.errors().isEmpty()
                ? "等待完整收盘数据" : PipelineMessageFormatter.detail(outcome.errors());
        step.nextRetryAt = retryAt;
        step.leaseUntil = null;
        step.finishedAt = now;
        step.updatedAt = now;
        updateStepFenced(step, owner, now);

        List<AiPipelineStep> steps = orderedSteps(run.id);
        updateCounts(run, steps);
        run.status = "WAITING_SOURCE";
        run.currentStep = step.stepKey;
        run.nextRetryAt = retryAt;
        run.errorMessage = step.errorMessage;
        run.errorDetail = step.errorDetail;
        run.finishedAt = null;
        run.updatedAt = now;
        updateRunFenced(run, owner, now);
    }

    private void failStep(AiPipelineRun run, AiPipelineStep step, RuntimeException exception, String owner) {
        String detail = rootMessage(exception);
        String message = PipelineMessageFormatter.summary(detail);
        LocalDateTime now = LocalDateTime.now();
        step.status = "FAILED";
        step.nextRetryAt = null;
        step.leaseUntil = null;
        step.errorMessage = message;
        step.errorDetail = PipelineMessageFormatter.detail(detail);
        step.finishedAt = now;
        step.updatedAt = now;
        updateStepFenced(step, owner, now);

        List<AiPipelineStep> steps = orderedSteps(run.id);
        updateCounts(run, steps);
        run.status = "FAILED";
        run.currentStep = step.stepKey;
        run.nextRetryAt = null;
        run.errorMessage = message;
        run.errorDetail = step.errorDetail;
        run.finishedAt = now;
        run.updatedAt = now;
        updateRunFenced(run, owner, now);
        try {
            executor.onPipelineFailure(context(run, new PipelineRequest(
                    run.tradeDate, run.strategyReleaseId, run.modelVersionId, run.idempotencyKey,
                    run.inputFingerprint, run.startedAt), steps, owner), step.stepKey, message);
        } catch (RuntimeException ignored) {
            // Failure reporting is best-effort; the original step failure remains authoritative.
        }
    }

    private void renewLease(Long runId, String owner) {
        LocalDateTime now = LocalDateTime.now();
        if (runMapper.renewExecution(runId, owner, now.plus(leaseDuration), now) != 1) {
            throw new LeaseLostException("全局日研究流水线执行租约已丢失，停止写入本次结果");
        }
    }

    private void updateRunFenced(AiPipelineRun run, String owner, LocalDateTime now) {
        if (runMapper.updateStateFenced(run, owner, now) != 1) {
            throw new LeaseLostException("全局日研究流水线执行租约已丢失，拒绝覆盖运行状态");
        }
    }

    private void updateStepFenced(AiPipelineStep step, String owner, LocalDateTime now) {
        if (stepMapper.updateStateFenced(step, owner, now) != 1) {
            throw new LeaseLostException("全局日研究流水线执行租约已丢失，拒绝覆盖步骤状态：" + step.stepKey);
        }
    }

    private static AiGlobalDailyResearchExecutor.StepOutcome validateOutcome(
            String stepKey,
            AiGlobalDailyResearchExecutor.StepOutcome outcome
    ) {
        if (outcome == null) {
            throw new IllegalStateException("步骤未返回执行结果：" + stepKey);
        }
        if (!List.of("SUCCESS", "SUCCESS_WITH_WARNINGS", "WAITING_SOURCE").contains(outcome.status())) {
            throw new IllegalStateException("步骤返回了无效状态：" + stepKey + " / " + outcome.status());
        }
        if (outcome.processedCount() < 0 || outcome.successCount() < 0 || outcome.failedCount() < 0
                || outcome.successCount() + outcome.failedCount() > outcome.processedCount()) {
            throw new IllegalStateException("步骤计数无效：" + stepKey);
        }
        if (outcome.outputFingerprint() == null || outcome.outputFingerprint().isBlank()) {
            throw new IllegalStateException("步骤缺少输出指纹：" + stepKey);
        }
        if (outcome.checkpointJson() == null || outcome.checkpointJson().isBlank()) {
            throw new IllegalStateException("步骤缺少可恢复 checkpoint：" + stepKey);
        }
        return outcome;
    }

    private static boolean canReuse(AiPipelineStep step, List<AiPipelineStep> steps) {
        boolean completed = ("SUCCESS".equals(step.status) || "SUCCESS_WITH_WARNINGS".equals(step.status))
                && step.outputFingerprint != null && !step.outputFingerprint.isBlank()
                && step.checkpointJson != null && !step.checkpointJson.isBlank();
        if (!completed) {
            return false;
        }
        if ("FETCH_SOURCE_DATA".equals(step.stepKey)) {
            return steps.stream().noneMatch(item -> "WAIT_DATA_READY".equals(item.stepKey)
                    && "WAITING_SOURCE".equals(item.status));
        }
        return true;
    }

    private static void updateCounts(AiPipelineRun run, List<AiPipelineStep> steps) {
        run.processedCount = steps.stream().mapToInt(step -> value(step.inputCount)).sum();
        run.successCount = steps.stream().mapToInt(step -> value(step.outputCount)).sum();
        run.failedCount = steps.stream().mapToInt(step -> {
            if ("FAILED".equals(step.status)) {
                return Math.max(1, value(step.inputCount) - value(step.outputCount));
            }
            return Math.max(0, value(step.inputCount) - value(step.outputCount));
        }).sum();
    }

    private static String warningSummary(List<AiPipelineStep> steps) {
        return PipelineMessageFormatter.summary(steps.stream()
                .filter(step -> "SUCCESS_WITH_WARNINGS".equals(step.status))
                .map(step -> step.stepKey + "：" + Objects.requireNonNullElse(step.errorMessage, "部分失败"))
                .toList());
    }

    private static String warningDetail(List<AiPipelineStep> steps) {
        return PipelineMessageFormatter.detail(steps.stream()
                .filter(step -> "SUCCESS_WITH_WARNINGS".equals(step.status))
                .map(step -> step.stepKey + "：" + Objects.requireNonNullElse(
                        step.errorDetail, Objects.requireNonNullElse(step.errorMessage, "部分失败")))
                .toList());
    }

    private static boolean isComplete(String status) {
        return "SUCCESS".equals(status) || "PARTIAL_SUCCESS".equals(status);
    }

    private static void assertSameRequest(AiPipelineRun run, PipelineRequest request) {
        if (!"GLOBAL".equals(run.scopeType) || run.ownerUserId != null
                || !Objects.equals(run.tradeDate, request.tradeDate())
                || !Objects.equals(run.strategyReleaseId, request.strategyReleaseId())
                || !Objects.equals(run.modelVersionId, request.modelVersionId())
                || !Objects.equals(run.inputFingerprint, request.inputFingerprint())) {
            throw new IllegalStateException("全局日研究幂等键已绑定不同输入，拒绝覆盖历史运行");
        }
    }

    private static void validate(PipelineRequest request) {
        if (request == null || request.tradeDate() == null || request.strategyReleaseId() == null
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.inputFingerprint() == null || request.inputFingerprint().isBlank()
                || request.startedAt() == null) {
            throw new IllegalArgumentException("全局日研究请求缺少交易日、策略、幂等键、输入指纹或开始时间");
        }
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private LeaseHeartbeat startHeartbeat(Long runId, String owner) {
        return new LeaseHeartbeat(runId, owner);
    }

    private static final class LeaseLostException extends IllegalStateException {
        private LeaseLostException(String message) {
            super(message);
        }
    }

    private final class LeaseHeartbeat implements AutoCloseable {
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final ScheduledFuture<?> future;

        private LeaseHeartbeat(Long runId, String owner) {
            long intervalMillis = Math.max(1L, heartbeatInterval.toMillis());
            future = HEARTBEAT_EXECUTOR.scheduleAtFixedRate(() -> {
                if (failure.get() != null) {
                    return;
                }
                try {
                    renewLease(runId, owner);
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
            Thread thread = new Thread(runnable, "maogou-global-research-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
    }
}
