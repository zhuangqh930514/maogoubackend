package com.maogou.stock.dto.research;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** JSON contracts for the operator-only historical fast-start workflow. */
public final class HistoricalFastStartPayloads {

    private HistoricalFastStartPayloads() {
    }

    public record PreviewRequest(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Integer targetTradingDays,
            Integer targetStocksPerDay,
            String mode,
            String featureVersion,
            String factorVersion,
            String labelVersion,
            String calendarVersion,
            String industryStandard
    ) {
        public PreviewRequest {
            mode = normalize(mode);
            featureVersion = normalize(featureVersion);
            factorVersion = normalize(factorVersion);
            labelVersion = normalize(labelVersion);
            calendarVersion = normalize(calendarVersion);
            industryStandard = normalize(industryStandard);
        }
    }

    public record CreateRequest(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Integer targetTradingDays,
            Integer targetStocksPerDay,
            String mode,
            String featureVersion,
            String factorVersion,
            String labelVersion,
            String calendarVersion,
            String industryStandard,
            String previewFingerprint,
            String idempotencyKey
    ) {
        public CreateRequest {
            mode = normalize(mode);
            featureVersion = normalize(featureVersion);
            factorVersion = normalize(factorVersion);
            labelVersion = normalize(labelVersion);
            calendarVersion = normalize(calendarVersion);
            industryStandard = normalize(industryStandard);
            previewFingerprint = normalize(previewFingerprint);
            idempotencyKey = normalize(idempotencyKey);
        }

        public PreviewRequest previewRequest() {
            return new PreviewRequest(endDate, targetTradingDays, targetStocksPerDay, mode,
                    featureVersion, factorVersion, labelVersion, calendarVersion, industryStandard);
        }
    }

    public record ShardQuery(
            String stageKey,
            String status,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            Integer page,
            Integer size
    ) {
        public ShardQuery {
            stageKey = normalize(stageKey);
            status = normalize(status);
        }

        public int safePage() {
            return page == null || page < 1 ? 1 : page;
        }

        public int safeSize() {
            return Math.min(200, Math.max(1, size == null ? 50 : size));
        }
    }

    public record IssueQuery(
            String reasonCode,
            String stockCode,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            Integer page,
            Integer size
    ) {
        public IssueQuery {
            reasonCode = normalize(reasonCode);
            stockCode = normalize(stockCode);
        }

        public int safePage() {
            return page == null || page < 1 ? 1 : page;
        }

        public int safeSize() {
            return Math.min(200, Math.max(1, size == null ? 50 : size));
        }
    }

    public record PreviewResult(
            String previewFingerprint,
            String configFingerprint,
            LocalDate effectiveRawStartDate,
            LocalDate effectiveSampleStartDate,
            LocalDate effectiveSampleEndDate,
            LocalDate latestMaturedDate,
            Integer targetTradingDays,
            Integer targetStocksPerDay,
            Integer replayTradingDays,
            Map<String, Object> reusable,
            Map<String, Object> planned,
            Map<String, Object> estimated,
            List<Map<String, Object>> capabilities,
            List<Map<String, Object>> blockingIssues,
            LocalDateTime expiresAt
    ) {
        public PreviewResult {
            reusable = immutableMap(reusable);
            planned = immutableMap(planned);
            estimated = immutableMap(estimated);
            capabilities = immutableList(capabilities);
            blockingIssues = immutableList(blockingIssues);
        }
    }

    public record RunView(
            Long id,
            String runKey,
            String mode,
            LocalDate requestedStartDate,
            LocalDate requestedEndDate,
            LocalDate effectiveSampleStartDate,
            LocalDate effectiveSampleEndDate,
            Integer targetTradingDays,
            Integer targetStocksPerDay,
            String featureVersion,
            String factorVersion,
            String labelVersion,
            String calendarVersion,
            String industryStandard,
            String status,
            String currentStage,
            Integer totalShards,
            Integer succeededShards,
            Integer quarantinedShards,
            Integer failedShards,
            Long readinessSnapshotId,
            String errorSummary,
            Long pipelineRunId,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime updatedAt
    ) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
        public PageResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ShardView(
            Long id,
            Long runId,
            String stageKey,
            LocalDate tradeDate,
            Integer bucketNo,
            String status,
            Integer attemptNo,
            Integer maxAttempts,
            Integer inputCount,
            Integer outputCount,
            Integer rejectedCount,
            String providerCode,
            String endpointType,
            LocalDateTime nextRetryAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            String errorCode,
            String errorMessage,
            String errorDetail
    ) {
    }

    public record IssueView(
            Long id,
            Long runId,
            Long shardId,
            String providerCode,
            String datasetCode,
            LocalDate tradeDate,
            String stockCode,
            String industryCode,
            Long rowNumber,
            String fieldName,
            String reasonCode,
            String reasonMessage,
            Boolean retryable,
            String resolutionStatus,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt
    ) {
    }

    public record ReadinessView(
            Long id,
            Long runId,
            LocalDateTime asOfTime,
            String featureVersion,
            String factorVersion,
            String labelVersion,
            Integer tradingDays,
            Integer stockCount,
            Integer tradabilityReady,
            Integer universeReady,
            Integer sectorReady,
            String status,
            String blockingGapsJson,
            String evidenceChecksum,
            LocalDateTime createdAt,
            String maturityLevel,
            String horizonCountsJson,
            String regimeDaysJson,
            Integer tradabilityEligible,
            java.math.BigDecimal tradabilityCoverage,
            Integer universeEligible,
            java.math.BigDecimal universeCoverage,
            Integer sectorEligible,
            java.math.BigDecimal sectorCoverage,
            String featureCoverageJson,
            String classDistributionJson,
            Integer leakageViolationCount,
            Integer duplicateCount,
            Integer mockSourceCount,
            Integer staleSourceCount,
            Integer inferredFactCount
    ) {
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(value);
    }

    private static <T> List<T> immutableList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
