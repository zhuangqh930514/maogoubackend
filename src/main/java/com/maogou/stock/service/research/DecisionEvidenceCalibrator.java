package com.maogou.stock.service.research;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DecisionEvidenceCalibrator {
    private final DecisionPolicyConfig config;

    public DecisionEvidenceCalibrator() {
        this(DecisionPolicyConfig.defaults());
    }

    public DecisionEvidenceCalibrator(DecisionPolicyConfig config) {
        this.config = config == null ? DecisionPolicyConfig.defaults() : config;
    }

    public Calibration calibrate(HorizonDecisionEvidence evidence) {
        if (evidence == null || evidence.rawPredictionSignal() == null) {
            return new Calibration(config.randomSignal(), BigDecimal.ZERO, "MISSING_EVIDENCE");
        }
        BigDecimal sampleStrength = clamp(BigDecimal.valueOf(evidence.evaluatedCount())
                .subtract(BigDecimal.valueOf(config.minimumEvaluatedSamples()))
                .divide(BigDecimal.valueOf(config.fullEvaluatedSamples() - config.minimumEvaluatedSamples()),
                        8, RoundingMode.HALF_UP));
        BigDecimal lower = normalize(evidence.wilsonLowerBound() == null
                ? evidence.hitRate() : evidence.wilsonLowerBound());
        BigDecimal qualityStrength = clamp(lower.subtract(config.wilsonBaseline())
                .divide(config.wilsonSpan(), 8, RoundingMode.HALF_UP));
        BigDecimal scopePenalty = scopePenalty(evidence.evidenceScope());
        BigDecimal strength = sampleStrength.multiply(qualityStrength).multiply(scopePenalty)
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal calibrated = config.randomSignal().add(normalize(evidence.rawPredictionSignal()).subtract(config.randomSignal())
                .multiply(strength)).setScale(8, RoundingMode.HALF_UP);
        String quality = evidence.evaluatedCount() < config.minimumEvaluatedSamples() ? "LOW_SAMPLE"
                : qualityStrength.signum() <= 0 ? "LOW_EVIDENCE" : "SUPPORTED";
        return new Calibration(calibrated, strength, quality);
    }

    private BigDecimal scopePenalty(String scope) {
        return switch (String.valueOf(scope).toUpperCase()) {
            case "STOCK_T1", "STOCK_T2", "STOCK_T3", "STOCK" -> config.stockScopePenalty();
            case "STRATEGY_FALLBACK", "TRANSITION_STRATEGY_FALLBACK" -> config.strategyScopePenalty();
            case "MARKET_REGIME" -> config.marketRegimeScopePenalty();
            default -> config.defaultScopePenalty();
        };
    }

    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) return DecisionPolicyConfig.defaults().randomSignal();
        BigDecimal normalized = value.compareTo(BigDecimal.ONE) > 0
                ? value.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP) : value;
        return normalized.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    public record Calibration(BigDecimal signal, BigDecimal evidenceStrength, String qualityStatus) {
    }
}
