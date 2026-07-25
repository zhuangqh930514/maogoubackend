package com.maogou.stock.dto.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only, operator-facing evidence for diagnosing the daily research pipeline.
 * None of these payloads is a trading signal or a substitute for a user daily report.
 */
public final class ResearchOperationsOverviewPayloads {

    private ResearchOperationsOverviewPayloads() {
    }

    public record Overview(
            LocalDateTime generatedAt,
            LocalDate tradeDate,
            int windowDays,
            TaskSummary tasks,
            SourceSummary sources,
            ModelFailureSummary modelFailures,
            DailyReportCoverage dailyReports,
            HoldingCoverage holdings,
            DecisionConflictSummary decisionConflicts,
            UniversePollutionSummary universePollution,
            UniverseLineageSummary universeLineage,
            List<Alert> alerts
    ) {
        public Overview {
            alerts = immutableList(alerts);
        }
    }

    public record TaskSummary(
            long totalRuns,
            Map<String, Long> statusCounts,
            Long latencyP50Millis,
            Long latencyP95Millis,
            long staleRunningCount,
            List<RunEvidence> attentionRuns
    ) {
        public TaskSummary {
            statusCounts = immutableMap(statusCounts);
            attentionRuns = immutableList(attentionRuns);
        }
    }

    public record RunEvidence(
            Long pipelineRunId,
            String scopeType,
            Long ownerUserId,
            LocalDate tradeDate,
            String pipelineType,
            String status,
            String currentStep,
            Integer processedCount,
            Integer successCount,
            Integer failedCount,
            Integer retryCount,
            LocalDateTime nextRetryAt,
            LocalDateTime leaseUntil,
            LocalDateTime updatedAt,
            String cause
    ) {
    }

    public record SourceSummary(
            List<SourceHealth> providers,
            List<Coverage> coverage
    ) {
        public SourceSummary {
            providers = immutableList(providers);
            coverage = immutableList(coverage);
        }
    }

    public record SourceHealth(
            Long id,
            String providerCode,
            String endpointType,
            String sourceStatus,
            LocalDateTime lastAttemptAt,
            LocalDateTime lastSuccessAt,
            Integer consecutiveFailureCount,
            LocalDateTime cooldownUntil,
            String cause
    ) {
    }

    public record Coverage(String status, long count) {
    }

    public record ModelFailureSummary(
            long totalFailures,
            Map<String, Long> groupedCounts,
            List<ModelFailure> recentFailures
    ) {
        public ModelFailureSummary {
            groupedCounts = immutableMap(groupedCounts);
            recentFailures = immutableList(recentFailures);
        }
    }

    public record ModelFailure(
            Long reportId,
            Long userId,
            String stockCode,
            String sourceModel,
            String failureType,
            String cause,
            LocalDateTime occurredAt
    ) {
    }

    public record DailyReportCoverage(
            long eligibleUserCount,
            long reportReadyUserCount,
            long missingReportUserCount,
            List<UserReportGap> missingUsers,
            List<ConsecutiveReportGap> consecutiveMissingUsers
    ) {
        public DailyReportCoverage {
            missingUsers = immutableList(missingUsers);
            consecutiveMissingUsers = immutableList(consecutiveMissingUsers);
        }

        public DailyReportCoverage(
                long eligibleUserCount,
                long reportReadyUserCount,
                long missingReportUserCount,
                List<UserReportGap> missingUsers
        ) {
            this(eligibleUserCount, reportReadyUserCount, missingReportUserCount, missingUsers, List.of());
        }
    }

    public record UserReportGap(Long userId, String displayName, boolean hasWatchlist, boolean hasHolding) {
    }

    public record ConsecutiveReportGap(Long userId, String displayName, String missingTradeDates) {
    }

    public record HoldingCoverage(
            long activeHoldingCount,
            long withoutDailyConclusionCount,
            List<HoldingGap> gaps
    ) {
        public HoldingCoverage {
            gaps = immutableList(gaps);
        }
    }

    public record HoldingGap(Long userId, String stockCode, String stockName, long netQuantity) {
    }

    public record DecisionConflictSummary(long conflictCount, long withoutReportCount, List<DecisionConflict> conflicts) {
        public DecisionConflictSummary {
            conflicts = immutableList(conflicts);
        }

        public DecisionConflictSummary(long conflictCount, List<DecisionConflict> conflicts) {
            this(conflictCount, 0, conflicts);
        }
    }

    public record DecisionConflict(
            Long userId,
            Long decisionItemId,
            Long reportId,
            String stockCode,
            String stockName,
            String decisionAction,
            String reportAction
    ) {
    }

    public record UniversePollutionSummary(long issueCount, List<UniversePollution> items) {
        public UniversePollutionSummary {
            items = immutableList(items);
        }
    }

    public record UniversePollution(
            Long universeItemId,
            String stockCode,
            String stockName,
            String sourceType,
            String listedStatus,
            String qualityStatus,
            String tradableStatus,
            String issueType,
            String cause
    ) {
    }

    /**
     * Source lineage is captured at snapshot creation. A later soft delete of a watchlist or
     * trade row must never be treated as historical pollution, so missing historic lineage is
     * reported as "not recorded" rather than inferred as a failure.
     */
    public record UniverseLineageSummary(long recordedCount, long invalidCount, List<UniverseLineageIssue> items) {
        public UniverseLineageSummary {
            items = immutableList(items);
        }
    }

    public record UniverseLineageIssue(
            Long universeItemId,
            String stockCode,
            String stockName,
            String sourceType,
            Long ownerUserId,
            Long sourceRecordId,
            Integer activeAtSnapshot,
            String cause
    ) {
    }

    /**
     * Null context fields mean the producer did not persist that evidence. They must not be
     * inferred by the UI as a successful source, a retry, or a valid stock state.
     */
    public record Alert(
            String id,
            String severity,
            String category,
            String title,
            Long pipelineRunId,
            String step,
            String stockCode,
            String providerCode,
            String cause,
            Integer retryCount,
            LocalDateTime nextRetryAt,
            Map<String, Object> context
    ) {
        public Alert {
            context = immutableMap(context);
        }
    }

    private static <T> List<T> immutableList(List<T> source) {
        return source == null || source.isEmpty() ? List.of() : List.copyOf(source);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
