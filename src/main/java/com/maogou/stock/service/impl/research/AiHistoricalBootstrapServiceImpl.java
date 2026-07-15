package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiHistoricalBootstrapService;
import com.maogou.stock.service.research.HistoricalUniverseSourceService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AiHistoricalBootstrapServiceImpl implements AiHistoricalBootstrapService {

    static final int CHECKPOINT_TRADING_DAYS = 20;
    private static final Duration MAX_HISTORY = Duration.ofDays(366L * 3L);
    private static final Duration LEASE_DURATION = Duration.ofHours(6);
    private static final List<String> REPLAY_STEPS = List.of(
            "BUILD_SAMPLES",
            "MATURE_SAMPLE_LABELS",
            "COMPUTE_FACTORS",
            "GENERATE_PREDICTIONS",
            "EVALUATE_PREDICTIONS"
    );

    private final AiPipelineRunMapper runMapper;
    private final AiPipelineStepMapper stepMapper;
    private final HistoricalUniverseSourceService sourceService;
    private final AiGlobalDailyResearchExecutor executor;
    private final ObjectMapper objectMapper;

    public AiHistoricalBootstrapServiceImpl(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            HistoricalUniverseSourceService sourceService,
            AiGlobalDailyResearchExecutor executor,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.sourceService = sourceService;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @Override
    public BootstrapResult run(BootstrapRequest request) {
        validate(request);
        AiPipelineRun run = findOrCreateRun(request);
        assertSameRequest(run, request);
        if ("SUCCESS".equals(run.status)) {
            List<AiPipelineStep> checkpoints = orderedSteps(run.id);
            return result("SUCCESS", run, checkpoints, completedTradingDays(checkpoints), List.of());
        }

        String owner = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (runMapper.claimExecution(run.id, owner, now.plus(LEASE_DURATION), now) != 1) {
            throw new IllegalStateException("历史冷启动正在由其他实例执行，请稍后查看运行记录");
        }
        try {
            run.executionOwner = owner;
            run.leaseUntil = now.plus(LEASE_DURATION);
            run.status = "RUNNING";
            run.currentStep = "VALIDATE_HISTORICAL_EVIDENCE";
            run.errorMessage = null;
            run.finishedAt = null;
            run.startedAt = run.startedAt == null ? request.requestedAt() : run.startedAt;
            run.updatedAt = now;
            updateRun(run, owner);

            EvidenceLoad evidenceLoad = loadEvidence(request, run, owner);
            if (!evidenceLoad.errors().isEmpty()) {
                String status = evidenceLoad.missingHistoricalUniverse()
                        ? "MISSING_HISTORICAL_UNIVERSE" : "FAILED";
                failRun(run, status, evidenceLoad.errors(), owner);
                return result(status, run, orderedSteps(run.id), 0, evidenceLoad.errors());
            }

            ensureSteps(run.id, evidenceLoad.evidence());
            List<AiPipelineStep> steps = orderedSteps(run.id);
            for (AiPipelineStep step : steps) {
                if ("SUCCESS".equals(step.status)) {
                    continue;
                }
                renewLease(run.id, owner);
                StepCheckpoint checkpoint = checkpoint(step);
                beginStep(run, step, owner);
                try {
                    replayBlock(run, step, checkpoint, evidenceLoad.byDate(), request, owner);
                    finishStep(step, checkpoint, owner);
                } catch (RuntimeException exception) {
                    failStep(run, step, checkpoint, exception, owner);
                    List<String> errors = List.of(rootMessage(exception));
                    return result("FAILED", run, orderedSteps(run.id),
                            completedTradingDays(orderedSteps(run.id)), errors);
                }
            }

            steps = orderedSteps(run.id);
            run.status = "SUCCESS";
            run.currentStep = steps.isEmpty() ? "NO_TRADING_DAYS" : steps.get(steps.size() - 1).stepKey;
            run.processedCount = evidenceLoad.evidence().size();
            run.successCount = evidenceLoad.evidence().size();
            run.failedCount = 0;
            run.errorMessage = null;
            run.finishedAt = LocalDateTime.now();
            run.updatedAt = run.finishedAt;
            updateRun(run, owner);
            return result("SUCCESS", run, steps, evidenceLoad.evidence().size(), List.of());
        } finally {
            runMapper.releaseExecution(run.id, owner, LocalDateTime.now());
        }
    }

    private EvidenceLoad loadEvidence(BootstrapRequest request, AiPipelineRun run, String owner) {
        List<HistoricalUniverseSourceService.HistoricalDayEvidence> evidence = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean missingUniverse = false;
        LocalDate cursor = request.startDate();
        while (!cursor.isAfter(request.endDate())) {
            renewLease(run.id, owner);
            LocalDateTime asOfTime = cursor.atTime(16, 0);
            HistoricalUniverseSourceService.HistoricalDayEvidence day = sourceService.load(cursor, asOfTime);
            if (day == null) {
                errors.add(cursor + "：历史来源服务没有返回证据");
                missingUniverse = true;
                break;
            }
            if ("NOT_TRADING_DAY".equals(day.status())) {
                cursor = cursor.plusDays(1);
                continue;
            }
            if (!day.ready()) {
                missingUniverse = "MISSING_HISTORICAL_UNIVERSE".equals(day.status());
                errors.add(cursor + "：" + (day.missingEvidence().isEmpty()
                        ? day.status() : String.join("；", day.missingEvidence())));
                break;
            }
            validateReadyEvidence(day, cursor, asOfTime);
            evidence.add(day);
            cursor = cursor.plusDays(1);
        }
        Map<LocalDate, HistoricalUniverseSourceService.HistoricalDayEvidence> byDate = new LinkedHashMap<>();
        evidence.forEach(item -> byDate.put(item.tradeDate(), item));
        return new EvidenceLoad(List.copyOf(evidence), Map.copyOf(byDate), List.copyOf(errors), missingUniverse);
    }

    private void replayBlock(
            AiPipelineRun run,
            AiPipelineStep step,
            StepCheckpoint checkpoint,
            Map<LocalDate, HistoricalUniverseSourceService.HistoricalDayEvidence> evidenceByDate,
            BootstrapRequest request,
            String owner
    ) {
        Set<LocalDate> completed = new LinkedHashSet<>(checkpoint.completedDates());
        for (LocalDate tradeDate : checkpoint.tradeDates()) {
            if (completed.contains(tradeDate)) {
                continue;
            }
            HistoricalUniverseSourceService.HistoricalDayEvidence evidence = evidenceByDate.get(tradeDate);
            if (evidence == null) {
                throw new IllegalStateException("checkpoint 对应的历史证据不存在：" + tradeDate);
            }
            Map<String, String> sourceCheckpoints = initialSourceCheckpoints(evidence);
            AiGlobalDailyResearchExecutor.PipelineContext context =
                    new AiGlobalDailyResearchExecutor.PipelineContext(
                            run.id, tradeDate, request.strategyReleaseId(), request.modelVersionId(),
                            request.idempotencyKey() + ":" + tradeDate,
                            evidence.sourceFingerprint(), evidence.asOfTime(), sourceCheckpoints,
                            () -> renewLease(run.id, owner));
            for (String replayStep : REPLAY_STEPS) {
                renewLease(run.id, owner);
                AiGlobalDailyResearchExecutor.StepOutcome outcome = executor.execute(replayStep, context);
                validateOutcome(replayStep, outcome);
                sourceCheckpoints.put(replayStep, outcome.checkpointJson());
            }
            completed.add(tradeDate);
            checkpoint.completedDates().clear();
            checkpoint.completedDates().addAll(completed);
            step.inputCount = checkpoint.tradeDates().size();
            step.outputCount = completed.size();
            step.checkpointJson = json(checkpoint);
            step.outputFingerprint = fingerprint(checkpoint);
            step.updatedAt = LocalDateTime.now();
            updateStep(step, owner);
        }
    }

    private Map<String, String> initialSourceCheckpoints(
            HistoricalUniverseSourceService.HistoricalDayEvidence evidence
    ) {
        Map<String, String> checkpoints = new LinkedHashMap<>();
        checkpoints.put("SNAPSHOT_UNIVERSE", json(Map.of(
                "universeSnapshotId", evidence.universeSnapshotId(),
                "includedCount", evidence.stockCount(),
                "historicalSourceFingerprint", evidence.sourceFingerprint())));
        checkpoints.put("FETCH_SOURCE_DATA", json(Map.of(
                "universeSnapshotId", evidence.universeSnapshotId(),
                "dataBatchId", evidence.dataBatchId(),
                "reusedPersistedHistoricalEvidence", true)));
        checkpoints.put("WAIT_DATA_READY", json(Map.of(
                "universeSnapshotId", evidence.universeSnapshotId(),
                "dataBatchId", evidence.dataBatchId(),
                "historicalEvidenceReady", true)));
        return checkpoints;
    }

    private AiPipelineRun findOrCreateRun(BootstrapRequest request) {
        LocalDateTime now = LocalDateTime.now();
        AiPipelineRun expected = new AiPipelineRun();
        expected.scopeType = "GLOBAL";
        expected.strategyReleaseId = request.strategyReleaseId();
        expected.modelVersionId = request.modelVersionId();
        expected.tradeDate = request.endDate();
        expected.pipelineType = "GLOBAL_HISTORICAL_BOOTSTRAP";
        expected.idempotencyKey = request.idempotencyKey();
        expected.inputFingerprint = requestFingerprint(request);
        expected.status = "PENDING";
        expected.retryCount = 0;
        expected.processedCount = 0;
        expected.successCount = 0;
        expected.failedCount = 0;
        expected.startedAt = request.requestedAt();
        expected.createdAt = now;
        expected.updatedAt = now;
        runMapper.insertIgnore(expected);
        AiPipelineRun stored = runMapper.selectByIdempotencyForUpdate(request.idempotencyKey());
        if (stored == null || stored.id == null) {
            throw new IllegalStateException("历史冷启动运行创建后未读取到记录");
        }
        return stored;
    }

    private void ensureSteps(
            Long runId,
            List<HistoricalUniverseSourceService.HistoricalDayEvidence> evidence
    ) {
        LocalDateTime now = LocalDateTime.now();
        int blockNo = 0;
        for (int offset = 0; offset < evidence.size(); offset += CHECKPOINT_TRADING_DAYS) {
            blockNo++;
            List<LocalDate> dates = evidence.subList(
                    offset, Math.min(offset + CHECKPOINT_TRADING_DAYS, evidence.size())).stream()
                    .map(HistoricalUniverseSourceService.HistoricalDayEvidence::tradeDate)
                    .toList();
            AiPipelineStep step = new AiPipelineStep();
            step.pipelineRunId = runId;
            step.stepKey = "REPLAY_%04d".formatted(blockNo);
            step.stepOrder = blockNo;
            step.status = "PENDING";
            step.retryCount = 0;
            step.inputCount = dates.size();
            step.outputCount = 0;
            step.checkpointJson = json(new StepCheckpoint(
                    dates.get(0), dates.get(dates.size() - 1), new ArrayList<>(dates), new ArrayList<>()));
            step.outputFingerprint = fingerprint(step.checkpointJson);
            step.createdAt = now;
            step.updatedAt = now;
            stepMapper.insertIgnore(step);
        }
    }

    private List<AiPipelineStep> orderedSteps(Long runId) {
        List<AiPipelineStep> steps = stepMapper.selectByRunIdForUpdate(runId);
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
                .filter(step -> step.stepKey != null && step.stepKey.startsWith("REPLAY_"))
                .sorted(Comparator.comparing(step -> step.stepOrder))
                .toList();
    }

    private void beginStep(AiPipelineRun run, AiPipelineStep step, String owner) {
        LocalDateTime now = LocalDateTime.now();
        if ("FAILED".equals(step.status) || "RUNNING".equals(step.status)) {
            step.retryCount = value(step.retryCount) + 1;
        }
        step.status = "RUNNING";
        step.errorMessage = null;
        step.startedAt = step.startedAt == null ? now : step.startedAt;
        step.finishedAt = null;
        step.updatedAt = now;
        updateStep(step, owner);
        run.currentStep = step.stepKey;
        run.updatedAt = now;
        updateRun(run, owner);
    }

    private void finishStep(AiPipelineStep step, StepCheckpoint checkpoint, String owner) {
        step.status = "SUCCESS";
        step.outputCount = checkpoint.tradeDates().size();
        step.checkpointJson = json(checkpoint);
        step.outputFingerprint = fingerprint(checkpoint);
        step.errorMessage = null;
        step.finishedAt = LocalDateTime.now();
        step.updatedAt = step.finishedAt;
        updateStep(step, owner);
    }

    private void failStep(
            AiPipelineRun run,
            AiPipelineStep step,
            StepCheckpoint checkpoint,
            RuntimeException exception,
            String owner
    ) {
        LocalDateTime now = LocalDateTime.now();
        step.status = "FAILED";
        step.outputCount = checkpoint.completedDates().size();
        step.checkpointJson = json(checkpoint);
        step.outputFingerprint = fingerprint(checkpoint);
        step.errorMessage = rootMessage(exception);
        step.finishedAt = now;
        step.updatedAt = now;
        updateStep(step, owner);
        run.status = "FAILED";
        run.failedCount = 1;
        run.processedCount = completedTradingDays(orderedSteps(run.id)) + 1;
        run.successCount = completedTradingDays(orderedSteps(run.id));
        run.errorMessage = step.stepKey + "：" + step.errorMessage;
        run.finishedAt = now;
        run.updatedAt = now;
        updateRun(run, owner);
    }

    private void failRun(AiPipelineRun run, String status, List<String> errors, String owner) {
        LocalDateTime now = LocalDateTime.now();
        run.status = status;
        run.failedCount = 1;
        run.errorMessage = String.join("；", errors);
        run.finishedAt = now;
        run.updatedAt = now;
        updateRun(run, owner);
    }

    private StepCheckpoint checkpoint(AiPipelineStep step) {
        try {
            return objectMapper.readValue(step.checkpointJson, StepCheckpoint.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("历史冷启动 checkpoint 无法解析：" + step.stepKey, exception);
        }
    }

    private void renewLease(Long runId, String owner) {
        LocalDateTime now = LocalDateTime.now();
        if (runMapper.renewExecution(runId, owner, now.plus(LEASE_DURATION), now) != 1) {
            throw new IllegalStateException("历史冷启动执行租约已丢失，停止写入");
        }
    }

    private void updateRun(AiPipelineRun run, String owner) {
        LocalDateTime now = run.updatedAt == null ? LocalDateTime.now() : run.updatedAt;
        if (runMapper.updateStateFenced(run, owner, now) != 1) {
            throw new IllegalStateException("历史冷启动执行租约已丢失，拒绝更新运行状态");
        }
    }

    private void updateStep(AiPipelineStep step, String owner) {
        LocalDateTime now = step.updatedAt == null ? LocalDateTime.now() : step.updatedAt;
        if (stepMapper.updateStateFenced(step, owner, now) != 1) {
            throw new IllegalStateException("历史冷启动执行租约已丢失，拒绝更新 checkpoint");
        }
    }

    private static void validateReadyEvidence(
            HistoricalUniverseSourceService.HistoricalDayEvidence evidence,
            LocalDate expectedDate,
            LocalDateTime expectedAsOf
    ) {
        if (!Objects.equals(evidence.tradeDate(), expectedDate)
                || evidence.asOfTime() == null || evidence.asOfTime().isAfter(expectedAsOf)
                || evidence.universeSnapshotId() == null || evidence.universeSnapshotId() <= 0
                || evidence.dataBatchId() == null || evidence.dataBatchId() <= 0
                || evidence.stockCount() == null || evidence.stockCount() <= 0
                || evidence.sourceFingerprint() == null || evidence.sourceFingerprint().isBlank()) {
            throw new IllegalArgumentException("READY 历史证据缺少当日股票池、数据批次或来源指纹：" + expectedDate);
        }
    }

    private static void validateOutcome(
            String step,
            AiGlobalDailyResearchExecutor.StepOutcome outcome
    ) {
        if (outcome == null || !List.of("SUCCESS", "SUCCESS_WITH_WARNINGS").contains(outcome.status())
                || outcome.checkpointJson() == null || outcome.checkpointJson().isBlank()
                || outcome.outputFingerprint() == null || outcome.outputFingerprint().isBlank()) {
            throw new IllegalStateException("历史回放步骤未成功或缺少不可变输出：" + step);
        }
    }

    private static void validate(BootstrapRequest request) {
        if (request == null || request.startDate() == null || request.endDate() == null
                || request.startDate().isAfter(request.endDate())
                || request.strategyReleaseId() == null || request.strategyReleaseId() <= 0
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.requestedAt() == null) {
            throw new IllegalArgumentException("历史冷启动缺少日期范围、全局策略、幂等键或请求时间");
        }
        if (Duration.between(request.startDate().atStartOfDay(),
                request.endDate().plusDays(1).atStartOfDay()).compareTo(MAX_HISTORY) > 0) {
            throw new IllegalArgumentException("历史冷启动最多允许回放 3 年");
        }
        if (request.endDate().isAfter(request.requestedAt().toLocalDate())) {
            throw new IllegalArgumentException("历史冷启动结束日期不能晚于请求日期");
        }
    }

    private static void assertSameRequest(AiPipelineRun run, BootstrapRequest request) {
        if (!"GLOBAL".equals(run.scopeType)
                || !"GLOBAL_HISTORICAL_BOOTSTRAP".equals(run.pipelineType)
                || !Objects.equals(run.strategyReleaseId, request.strategyReleaseId())
                || !Objects.equals(run.modelVersionId, request.modelVersionId())
                || !Objects.equals(run.tradeDate, request.endDate())
                || !Objects.equals(run.inputFingerprint, requestFingerprint(request))) {
            throw new IllegalStateException("历史冷启动幂等键已绑定不同输入，拒绝覆盖原运行");
        }
    }

    private int completedTradingDays(List<AiPipelineStep> steps) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (AiPipelineStep step : steps) {
            dates.addAll(checkpoint(step).completedDates());
        }
        return dates.size();
    }

    private BootstrapResult result(
            String status,
            AiPipelineRun run,
            List<AiPipelineStep> checkpoints,
            int completed,
            List<String> errors
    ) {
        return new BootstrapResult(status, run, checkpoints, completed, errors);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化历史冷启动证据", exception);
        }
    }

    private static String requestFingerprint(BootstrapRequest request) {
        return fingerprint(String.join("|",
                "GLOBAL_HISTORICAL_BOOTSTRAP/1.0.0",
                request.startDate().toString(), request.endDate().toString(),
                String.valueOf(request.strategyReleaseId()), String.valueOf(request.modelVersionId())));
    }

    private static String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
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
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record EvidenceLoad(
            List<HistoricalUniverseSourceService.HistoricalDayEvidence> evidence,
            Map<LocalDate, HistoricalUniverseSourceService.HistoricalDayEvidence> byDate,
            List<String> errors,
            boolean missingHistoricalUniverse
    ) {
    }

    private record StepCheckpoint(
            LocalDate startDate,
            LocalDate endDate,
            List<LocalDate> tradeDates,
            List<LocalDate> completedDates
    ) {
        private StepCheckpoint {
            tradeDates = tradeDates == null ? new ArrayList<>() : new ArrayList<>(tradeDates);
            completedDates = completedDates == null ? new ArrayList<>() : new ArrayList<>(completedDates);
        }
    }
}
