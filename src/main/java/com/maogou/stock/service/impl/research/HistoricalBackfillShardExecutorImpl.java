package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillShard;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import com.maogou.stock.service.research.HistoricalBackfillShardExecutor;
import com.maogou.stock.service.research.HistoricalUniverseSourceService;
import com.maogou.stock.service.research.HistoricalReadinessEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Real shard worker for the historical fast-start pipeline.
 *
 * <p>All external I/O and expensive research work happens outside the
 * coordinator's short state-update statements. Each replay date and each
 * finalization batch writes a durable checkpoint before the next unit starts.</p>
 */
@Service
public class HistoricalBackfillShardExecutorImpl implements HistoricalBackfillShardExecutor {

    private static final int REPLAY_BLOCK_DAYS = 20;
    private static final int FINALIZE_BATCH_SIZE = 2_000;
    private static final List<String> REPLAY_STEPS = List.of(
            "BUILD_SAMPLES", "COMPUTE_FACTORS", "GENERATE_PREDICTIONS");
    private static final List<String> FINALIZE_STEPS = List.of(
            "MATURE_HISTORICAL_SAMPLE_LABELS", "EVALUATE_HISTORICAL_PREDICTIONS");

    private final AiHistoricalEvidenceImportService evidenceImportService;
    private final HistoricalUniverseSourceService sourceService;
    private final AiGlobalDailyResearchExecutor researchExecutor;
    private final HistoricalReadinessEvaluator readinessEvaluator;
    private final ObjectMapper objectMapper;

    @Autowired
    public HistoricalBackfillShardExecutorImpl(
            AiHistoricalEvidenceImportService evidenceImportService,
            HistoricalUniverseSourceService sourceService,
            AiGlobalDailyResearchExecutor researchExecutor,
            HistoricalReadinessEvaluator readinessEvaluator,
            ObjectMapper objectMapper
    ) {
        this.evidenceImportService = evidenceImportService;
        this.sourceService = sourceService;
        this.researchExecutor = researchExecutor;
        this.readinessEvaluator = readinessEvaluator == null
                ? HistoricalReadinessEvaluator.noop() : readinessEvaluator;
        this.objectMapper = objectMapper;
    }

    public HistoricalBackfillShardExecutorImpl(
            AiHistoricalEvidenceImportService evidenceImportService,
            HistoricalUniverseSourceService sourceService,
            AiGlobalDailyResearchExecutor researchExecutor,
            ObjectMapper objectMapper
    ) {
        this(evidenceImportService, sourceService, researchExecutor,
                HistoricalReadinessEvaluator.noop(), objectMapper);
    }

    @Override
    public ExecutionResult execute(ExecutionCommand command) {
        validate(command);
        String stage = command.shard().stageKey;
        return switch (stage) {
            case "IMPORT_HISTORICAL_EVIDENCE" -> importEvidence(command);
            case "REPLAY_BLOCK" -> replayBlock(command);
            case "MATURE_HISTORICAL_SAMPLE_LABELS" -> drainFinalStep(command,
                    "MATURE_HISTORICAL_SAMPLE_LABELS");
            case "EVALUATE_HISTORICAL_PREDICTIONS" -> drainFinalStep(command,
                    "EVALUATE_HISTORICAL_PREDICTIONS");
            case "READINESS_CHECK" -> readinessCheck(command);
            default -> throw new IllegalArgumentException("未知历史分片阶段：" + stage);
        };
    }

    private ExecutionResult importEvidence(ExecutionCommand command) {
        List<LocalDate> dates = shardDates(command);
        AiHistoricalEvidenceImportService.ColdStartPlan shardPlan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                dates.get(0), dates.get(dates.size() - 1), dates.size(),
                command.plan().replayTradingDays(), command.plan().targetStockCount(), dates);
        AiHistoricalEvidenceImportService.ImportResult result = evidenceImportService.importEvidence(
                new AiHistoricalEvidenceImportService.ImportRequest(
                        shardPlan,
                        command.idempotencyKey() + ":IMPORT:" + command.shard().bucketNo,
                        command.requestedAt(), command.runId()));
        List<String> warnings = result.warnings();
        String checkpoint = json(Map.of(
                "stage", "IMPORT_HISTORICAL_EVIDENCE",
                "dates", dates,
                "importedTradingDays", result.importedTradingDays(),
                "reusedTradingDays", result.reusedTradingDays(),
                "preparedStocks", result.preparedStocks(),
                "warnings", warnings));
        return result(warnings.isEmpty() ? "SUCCESS" : "SUCCESS_WITH_WARNINGS",
                dates.size(), result.importedTradingDays() + result.reusedTradingDays(),
                0, warnings.size(), checkpoint, result.sourceFingerprint(),
                "HISTORICAL_PROVIDER", "SECURITY_CATALOG/DAILY_BAR", null,
                warnings.isEmpty() ? null : "HISTORICAL_IMPORT_WARNINGS",
                warnings.isEmpty() ? null : summarize(warnings),
                warnings.isEmpty() ? null : detail(warnings));
    }

    private ExecutionResult replayBlock(ExecutionCommand command) {
        List<LocalDate> dates = shardDates(command);
        Set<LocalDate> completed = completedDates(command.shard().checkpointJson);
        int rejected = number(command.shard().rejectedCount);
        List<String> warnings = new ArrayList<>();
        for (LocalDate tradeDate : dates) {
            command.leaseGuard().checkpoint();
            if (completed.contains(tradeDate)) {
                continue;
            }
            HistoricalUniverseSourceService.HistoricalDayEvidence evidence =
                    sourceService.load(tradeDate, tradeDate.atTime(16, 0));
            if (evidence == null || !evidence.ready()) {
                String reason = evidence == null ? "历史来源服务没有返回证据"
                        : String.join("；", evidence.missingEvidence());
                throw new RetryableShardException("HISTORICAL_EVIDENCE_UNAVAILABLE",
                        tradeDate + "：" + reason, "REPLAY_BLOCK", null);
            }
            Map<String, String> sourceCheckpoints = initialSourceCheckpoints(evidence);
            AiGlobalDailyResearchExecutor.PipelineContext context =
                    new AiGlobalDailyResearchExecutor.PipelineContext(
                            command.runId(), tradeDate, command.strategyReleaseId(), command.modelVersionId(),
                            command.idempotencyKey() + ":REPLAY:" + tradeDate,
                            evidence.sourceFingerprint(), evidence.asOfTime(), command.attemptNo(),
                            sourceCheckpoints, command.leaseGuard()::checkpoint, command.runId());
            int processed = 0;
            for (String step : REPLAY_STEPS) {
                command.leaseGuard().checkpoint();
                AiGlobalDailyResearchExecutor.StepOutcome outcome = researchExecutor.execute(step, context);
                validateOutcome(step, outcome);
                processed += outcome.processedCount();
                warnings.addAll(outcome.errors());
                sourceCheckpoints.put(step, outcome.checkpointJson());
            }
            completed.add(tradeDate);
            String checkpoint = json(Map.of(
                    "stage", "REPLAY_BLOCK",
                    "dates", dates,
                    "completedDates", completed,
                    "lastTradeDate", tradeDate,
                    "warnings", warnings));
            command.checkpointWriter().write(checkpoint, completed.size(), rejected);
        }
        String checkpoint = json(Map.of("stage", "REPLAY_BLOCK", "dates", dates,
                "completedDates", completed, "warnings", warnings));
        int success = completed.size();
        return result(warnings.isEmpty() ? "SUCCESS" : "SUCCESS_WITH_WARNINGS", dates.size(), success,
                Math.max(0, dates.size() - success), rejected, checkpoint,
                sha256(checkpoint), "PERSISTED_EVIDENCE", "HISTORICAL_REPLAY", null,
                warnings.isEmpty() ? null : "HISTORICAL_REPLAY_WARNINGS",
                warnings.isEmpty() ? null : summarize(warnings),
                warnings.isEmpty() ? null : detail(warnings));
    }

    private ExecutionResult drainFinalStep(ExecutionCommand command, String step) {
        LocalDate tradeDate = command.plan().tradingDates().get(command.plan().tradingDates().size() - 1);
        int processed = number(command.shard().outputCount);
        int succeeded = processed;
        int failed = number(command.shard().rejectedCount);
        List<String> warnings = new ArrayList<>();
        Map<String, String> checkpoints = new LinkedHashMap<>();
        String persisted = command.shard().checkpointJson;
        if (persisted != null && !persisted.isBlank()) {
            checkpoints.put(step, persisted);
        }
        do {
            command.leaseGuard().checkpoint();
            AiGlobalDailyResearchExecutor.PipelineContext context =
                    new AiGlobalDailyResearchExecutor.PipelineContext(
                            command.runId(), tradeDate, command.strategyReleaseId(), command.modelVersionId(),
                            command.idempotencyKey() + ":" + step,
                            command.idempotencyKey(), tradeDate.atTime(16, 0), command.attemptNo(),
                            checkpoints, command.leaseGuard()::checkpoint, command.runId());
            AiGlobalDailyResearchExecutor.StepOutcome outcome = researchExecutor.execute(step, context);
            validateOutcome(step, outcome);
            processed = Math.addExact(processed, outcome.processedCount());
            succeeded = Math.addExact(succeeded, outcome.successCount());
            failed = Math.addExact(failed, outcome.failedCount());
            warnings.addAll(outcome.errors());
            checkpoints.put(step, outcome.checkpointJson());
            String checkpoint = json(Map.of(
                    "stage", step,
                    "processedCount", processed,
                    "successCount", succeeded,
                    "failedCount", failed,
                    "warnings", warnings,
                    "lastCheckpoint", outcome.checkpointJson()));
            command.checkpointWriter().write(checkpoint, succeeded, failed);
            if (outcome.processedCount() == 0
                    || outcome.processedCount() < FINALIZE_BATCH_SIZE
                    || outcome.successCount() == 0) {
                break;
            }
        } while (true);
        return result(warnings.isEmpty() ? "SUCCESS" : "SUCCESS_WITH_WARNINGS", processed, succeeded, failed,
                failed, json(Map.of("stage", step, "processedCount", processed,
                        "successCount", succeeded, "failedCount", failed, "warnings", warnings)),
                sha256(step + "|" + processed + "|" + succeeded + "|" + failed + "|" + warnings),
                "PERSISTED_EVIDENCE", "HISTORICAL_REPLAY", null,
                warnings.isEmpty() ? null : "HISTORICAL_FINALIZE_WARNINGS",
                warnings.isEmpty() ? null : summarize(warnings),
                warnings.isEmpty() ? null : detail(warnings));
    }

    private ExecutionResult readinessCheck(ExecutionCommand command) {
        LocalDate startDate = command.plan().tradingDates().get(0);
        int sampleEndIndex = Math.min(command.plan().trainingTradingDays(),
                command.plan().tradingDates().size()) - 1;
        LocalDate endDate = command.plan().tradingDates().get(Math.max(0, sampleEndIndex));
        HistoricalReadinessEvaluator.Evaluation evaluation = readinessEvaluator.evaluate(
                new HistoricalReadinessEvaluator.Request(
                        command.runId(), firstNonBlank(command.featureVersion(), "POINT_IN_TIME/1.1.0"),
                        firstNonBlank(command.factorVersion(), "FACTOR/1.1.0"),
                        firstNonBlank(command.labelVersion(), "LABEL/1.1.0"),
                        firstNonBlank(command.calendarVersion(), "CALENDAR/1.0.0"),
                        startDate, endDate, LocalDateTime.now()));
        String checkpoint = json(Map.of(
                "stage", "READINESS_CHECK",
                "status", evaluation.status(),
                "maturityLevel", evaluation.maturityLevel(),
                "runId", command.runId(),
                "horizonCounts", evaluation.horizonCounts(),
                "blockingGaps", evaluation.blockingGaps(),
                "evidenceChecksum", evaluation.evidenceChecksum()));
        boolean ready = "READY".equals(evaluation.status());
        if (ready) {
            return result("SUCCESS", 1, 1, 0, 0, checkpoint, evaluation.evidenceChecksum(),
                    "SYSTEM", "READINESS", null, null,
                    "历史事实已通过当前版本训练准入检查", null);
        }
        String blockedStatus = "BLOCKED_BY_QUALITY".equals(evaluation.status())
                ? "BLOCKED_BY_QUALITY" : "INSUFFICIENT_DATA";
        String errorCode = "BLOCKED_BY_QUALITY".equals(blockedStatus)
                ? "HISTORICAL_READINESS_BLOCKED" : "HISTORICAL_READINESS_INSUFFICIENT";
        String message = "BLOCKED_BY_QUALITY".equals(blockedStatus)
                ? "历史事实未通过质量门，禁止把本次运行标记为成功"
                : "历史事实数量不足，禁止把本次运行标记为成功";
        return result(blockedStatus, 1, 0, 1, evaluation.blockingGaps().size(), checkpoint,
                evaluation.evidenceChecksum(), "SYSTEM", "READINESS", null, errorCode,
                message, String.join("\n", evaluation.blockingGaps()));
    }

    private List<LocalDate> shardDates(ExecutionCommand command) {
        AiHistoricalBackfillShard shard = command.shard();
        if ("READINESS_CHECK".equals(shard.stageKey)
                || "MATURE_HISTORICAL_SAMPLE_LABELS".equals(shard.stageKey)
                || "EVALUATE_HISTORICAL_PREDICTIONS".equals(shard.stageKey)) {
            return command.plan().tradingDates();
        }
        int bucket = Math.max(1, number(shard.bucketNo));
        int from = Math.multiplyExact(bucket - 1, REPLAY_BLOCK_DAYS);
        if (from >= command.plan().tradingDates().size()) {
            throw new IllegalArgumentException("历史分片超出计划交易日范围：" + shard.id);
        }
        int to = Math.min(from + REPLAY_BLOCK_DAYS, command.plan().tradingDates().size());
        return command.plan().tradingDates().subList(from, to);
    }

    private static Set<LocalDate> completedDates(String checkpointJson) {
        if (checkpointJson == null || checkpointJson.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            JsonNode root = new ObjectMapper().readTree(checkpointJson);
            Set<LocalDate> result = new LinkedHashSet<>();
            JsonNode dates = root.path("completedDates");
            if (dates.isArray()) {
                for (JsonNode date : dates) {
                    if (date.isTextual()) {
                        result.add(LocalDate.parse(date.asText()));
                    } else if (date.isArray() && date.size() == 3
                            && date.get(0).canConvertToInt()
                            && date.get(1).canConvertToInt()
                            && date.get(2).canConvertToInt()) {
                        // Accept the pre-ISO Jackson timestamp-array format so an
                        // interrupted run created before this fix can resume safely.
                        result.add(LocalDate.of(date.get(0).intValue(), date.get(1).intValue(),
                                date.get(2).intValue()));
                    }
                }
            }
            return result;
        } catch (Exception ignored) {
            return new LinkedHashSet<>();
        }
    }

    private static Map<String, String> initialSourceCheckpoints(
            HistoricalUniverseSourceService.HistoricalDayEvidence evidence
    ) {
        Map<String, String> checkpoints = new LinkedHashMap<>();
        checkpoints.put("SNAPSHOT_UNIVERSE", jsonStatic(Map.of(
                "universeSnapshotId", evidence.universeSnapshotId(),
                "includedCount", evidence.stockCount(),
                "historicalSourceFingerprint", evidence.sourceFingerprint())));
        checkpoints.put("FETCH_SOURCE_DATA", jsonStatic(Map.of(
                "universeSnapshotId", evidence.universeSnapshotId(),
                "dataBatchId", evidence.dataBatchId(),
                "reusedPersistedHistoricalEvidence", true)));
        checkpoints.put("WAIT_DATA_READY", jsonStatic(Map.of(
                "universeSnapshotId", evidence.universeSnapshotId(),
                "dataBatchId", evidence.dataBatchId(),
                "historicalEvidenceReady", true)));
        return checkpoints;
    }

    private static void validate(ExecutionCommand command) {
        if (command == null || command.runId() == null || command.runId() <= 0
                || command.shard() == null || command.plan() == null
                || command.plan().tradingDates().isEmpty()
                || command.strategyReleaseId() == null || command.strategyReleaseId() <= 0
                || command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("历史分片缺少运行、计划、策略或幂等信息");
        }
    }

    private static void validateOutcome(String step, AiGlobalDailyResearchExecutor.StepOutcome outcome) {
        if (outcome == null || !List.of("SUCCESS", "SUCCESS_WITH_WARNINGS").contains(outcome.status())
                || outcome.checkpointJson() == null || outcome.checkpointJson().isBlank()
                || outcome.outputFingerprint() == null || outcome.outputFingerprint().isBlank()) {
            throw new IllegalStateException("历史回放步骤未成功或缺少不可变输出：" + step);
        }
    }

    private ExecutionResult result(
            String status, int processed, int succeeded, int failed, int rejected,
            String checkpoint, String fingerprint, String provider, String endpoint,
            LocalDateTime nextRetryAt, String errorCode, String message, String detail
    ) {
        return new ExecutionResult(status, processed, succeeded, failed, rejected,
                checkpoint, fingerprint, provider, endpoint, nextRetryAt, errorCode, message, detail);
    }

    private String json(Object value) {
        try {
            return objectMapper.copy()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存历史分片 checkpoint", exception);
        }
    }

    private static String jsonStatic(Object value) {
        try {
            return new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成历史来源 checkpoint", exception);
        }
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String summarize(List<String> values) {
        return values == null ? null : values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).limit(12).reduce((left, right) -> left + "；" + right)
                .orElse(null);
    }

    private static String detail(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("\n", values);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    static final class RetryableShardException extends RuntimeException {
        private final String errorCode;
        private final String endpointType;
        private final String providerCode;

        RetryableShardException(String errorCode, String message, String endpointType, String providerCode) {
            super(message);
            this.errorCode = errorCode;
            this.endpointType = endpointType;
            this.providerCode = providerCode;
        }
    }
}
