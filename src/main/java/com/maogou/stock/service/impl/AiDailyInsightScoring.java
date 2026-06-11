package com.maogou.stock.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

public final class AiDailyInsightScoring {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MIN_RELIABLE_SAMPLE_COUNT = 10;
    private static final Set<String> POSITIVE_ACTIONS = Set.of("BUY", "HOLD");
    private static final Set<String> NEGATIVE_ACTIONS = Set.of("REDUCE", "SELL");

    private AiDailyInsightScoring() {
    }

    public static Decision classify(Input input) {
        BigDecimal systemScore = clamp(percent(input.systemScore()));
        BigDecimal riskScore = clamp(percent(input.riskScore()));
        BigDecimal dataQualityScore = clamp(percent(input.dataQualityScore()));
        BigDecimal freshnessScore = clamp(percent(input.freshnessScore()));
        BigDecimal aiConfidenceScore = clamp(normalizeConfidence(input.aiConfidence()));
        String aiDecision = normalizeAction(input.aiDecision());
        boolean hasStructuredDecision = !aiDecision.isBlank();
        String normalizedDecision = hasStructuredDecision ? aiDecision : "WATCH";

        History history = effectiveHistory(input.stockHitRate(), input.stockSampleCount(), input.factorHitRate(), input.factorSampleCount());
        BigDecimal composite = systemScore.multiply(new BigDecimal("0.50"))
                .add(aiConfidenceScore.multiply(new BigDecimal("0.25")))
                .add(history.hitRate().multiply(new BigDecimal("0.15")))
                .add(dataQualityScore.add(freshnessScore).divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP).multiply(new BigDecimal("0.10")));

        if (dataQualityScore.compareTo(new BigDecimal("60")) < 0) {
            return decision("REDUCE", "AVOID", composite, "DATA_WEAK", history);
        }
        if (!hasStructuredDecision) {
            return decision("WATCH", "WATCH", composite, "AI_DECISION_MISSING", history);
        }
        if (history.sampleCount() < MIN_RELIABLE_SAMPLE_COUNT) {
            return decision("WATCH", "WATCH", composite, "LOW_SAMPLE", history);
        }
        if (history.hitRate().compareTo(new BigDecimal("45")) < 0) {
            return decision("REDUCE", "AVOID", composite, "HISTORY_WEAK", history);
        }
        if (NEGATIVE_ACTIONS.contains(normalizedDecision) || riskScore.compareTo(new BigDecimal("72")) >= 0) {
            return decision(normalizedDecision.equals("SELL") ? "SELL" : "REDUCE", "AVOID", composite, "READY", history);
        }
        if (POSITIVE_ACTIONS.contains(normalizedDecision)
                && composite.compareTo(new BigDecimal("65")) >= 0
                && riskScore.compareTo(new BigDecimal("65")) <= 0
                && dataQualityScore.compareTo(new BigDecimal("70")) >= 0) {
            return decision(normalizedDecision, "RECOMMEND", composite, history.source(), history);
        }
        return decision("WATCH", "WATCH", composite, "READY", history);
    }

    private static Decision decision(String action, String bucket, BigDecimal composite, String confidenceLevel, History history) {
        return new Decision(
                action,
                bucket,
                composite.setScale(2, RoundingMode.HALF_UP),
                confidenceLevel,
                history.hitRate().setScale(2, RoundingMode.HALF_UP),
                history.sampleCount(),
                history.source()
        );
    }

    private static History effectiveHistory(BigDecimal stockHitRate, Integer stockSampleCount, BigDecimal factorHitRate, Integer factorSampleCount) {
        int stockCount = stockSampleCount == null ? 0 : Math.max(0, stockSampleCount);
        if (stockCount >= MIN_RELIABLE_SAMPLE_COUNT) {
            return new History(clamp(percent(stockHitRate)), stockCount, "READY");
        }
        int factorCount = factorSampleCount == null ? 0 : Math.max(0, factorSampleCount);
        if (factorCount >= MIN_RELIABLE_SAMPLE_COUNT) {
            return new History(clamp(percent(factorHitRate)), factorCount, "FACTOR_PROXY");
        }
        return new History(ZERO, Math.max(stockCount, factorCount), "LOW_SAMPLE");
    }

    private static String normalizeAction(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (Set.of("BUY", "HOLD", "REDUCE", "SELL", "WATCH").contains(normalized)) {
            return normalized;
        }
        return "";
    }

    private static BigDecimal normalizeConfidence(BigDecimal value) {
        BigDecimal safe = value == null ? ZERO : value;
        if (safe.compareTo(BigDecimal.ONE) <= 0) {
            return safe.multiply(ONE_HUNDRED);
        }
        return safe;
    }

    private static BigDecimal percent(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static BigDecimal clamp(BigDecimal value) {
        BigDecimal safe = value == null ? ZERO : value;
        if (safe.compareTo(ZERO) < 0) {
            return ZERO;
        }
        if (safe.compareTo(ONE_HUNDRED) > 0) {
            return ONE_HUNDRED;
        }
        return safe;
    }

    public record Input(
            BigDecimal systemScore,
            BigDecimal riskScore,
            BigDecimal dataQualityScore,
            BigDecimal freshnessScore,
            String aiDecision,
            BigDecimal aiConfidence,
            BigDecimal stockHitRate,
            Integer stockSampleCount,
            BigDecimal factorHitRate,
            Integer factorSampleCount
    ) {
    }

    public record Decision(
            String finalAction,
            String actionBucket,
            BigDecimal compositeScore,
            String confidenceLevel,
            BigDecimal effectiveHitRate,
            Integer effectiveSampleCount,
            String historySource
    ) {
    }

    private record History(BigDecimal hitRate, int sampleCount, String source) {
    }
}
