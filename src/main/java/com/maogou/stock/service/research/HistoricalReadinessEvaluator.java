package com.maogou.stock.service.research;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Calculates a versioned, persisted readiness snapshot from real research facts.
 * This evaluator is deliberately separate from the fast-start coordinator so a
 * coordinator cannot manufacture readiness from its requested target size.
 */
public interface HistoricalReadinessEvaluator {

    Evaluation evaluate(Request request);

    static HistoricalReadinessEvaluator noop() {
        return request -> Evaluation.empty(request == null ? null : request.asOfTime());
    }

    record Request(
            Long runId,
            String featureVersion,
            String factorVersion,
            String labelVersion,
            String calendarVersion,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime asOfTime
    ) {
    }

    record Evaluation(
            String status,
            String maturityLevel,
            int tradingDays,
            int stockCount,
            Map<Integer, Integer> horizonCounts,
            Map<String, Integer> regimeDays,
            int tradabilityEligible,
            int tradabilityReady,
            BigDecimal tradabilityCoverage,
            int universeEligible,
            int universeReady,
            BigDecimal universeCoverage,
            int sectorEligible,
            int sectorReady,
            BigDecimal sectorCoverage,
            Map<String, Map<String, Integer>> featureCoverage,
            Map<String, Integer> classDistribution,
            int leakageViolationCount,
            int duplicateCount,
            int mockSourceCount,
            int staleSourceCount,
            int inferredFactCount,
            List<String> blockingGaps,
            String evidenceChecksum
    ) {
        public static Evaluation empty(LocalDateTime asOfTime) {
            return new Evaluation(
                    "INSUFFICIENT_DATA", "R0_RULES_LIVE", 0, 0, Map.of(), Map.of(),
                    0, 0, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO,
                    0, 0, BigDecimal.ZERO, Map.of(), Map.of(),
                    0, 0, 0, 0, 0,
                    List.of("READINESS_EVALUATOR_NOT_CONFIGURED"),
                    "UNVERIFIED:" + String.valueOf(asOfTime));
        }
    }
}
