package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.research.DecisionEvidenceCalibrator;
import com.maogou.stock.service.research.DecisionPolicyConfig;
import com.maogou.stock.service.research.HorizonDecisionEvidence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * Versioned shadow policy. It deliberately does not implement the active policy
 * interface so a component wiring mistake cannot silently replace production decisions.
 */
public final class DecisionPolicyShadow {
    public static final String VERSION = "DECISION/2.0.0";
    private final DecisionPolicyConfig config;
    private final DecisionEvidenceCalibrator calibrator;

    public DecisionPolicyShadow() {
        this(DecisionPolicyConfig.defaults());
    }

    public DecisionPolicyShadow(DecisionPolicyConfig config) {
        this.config = config == null ? DecisionPolicyConfig.defaults() : config;
        this.calibrator = new DecisionEvidenceCalibrator(this.config);
    }

    public Decision decide(Input input) {
        if (input == null) throw new IllegalArgumentException("影子决策输入不能为空");
        if (input.unavailableReason() != null && !input.unavailableReason().isBlank()) {
            return Decision.unavailable(input.unavailableReason());
        }
        DecisionEvidenceCalibrator.Calibration t1 = calibrator.calibrate(input.t1());
        DecisionEvidenceCalibrator.Calibration t2 = calibrator.calibrate(input.t2());
        DecisionEvidenceCalibrator.Calibration t3 = calibrator.calibrate(input.t3());
        BigDecimal horizon = t1.signal().multiply(config.t1Weight())
                .add(t2.signal().multiply(config.t2Weight()))
                .add(t3.signal().multiply(config.t3Weight()));
        BigDecimal factor = normalize(input.factorSupportSignal());
        BigDecimal strategy = normalize(input.strategyValidationSignal());
        BigDecimal core = horizon.multiply(config.horizonWeight())
                .add(factor.multiply(config.factorWeight()))
                .add(strategy.multiply(config.strategyWeight()));
        BigDecimal quality = normalize(input.dataQuality());
        BigDecimal score = clamp(new BigDecimal("50").add(new BigDecimal("100")
                .multiply(core.subtract(config.randomSignal())).multiply(quality))).setScale(4, RoundingMode.HALF_UP);
        BigDecimal risk = input.riskScore() == null ? null : input.riskScore().max(BigDecimal.ZERO).min(new BigDecimal("100"));
        boolean enoughEvidence = List.of(input.t1(), input.t2(), input.t3()).stream()
                .allMatch(item -> item != null && item.evaluatedCount() >= config.minimumEvaluatedSamples()
                        && DecisionEvidenceCalibrator.normalize(item.wilsonLowerBound() == null ? item.hitRate() : item.wilsonLowerBound())
                        .compareTo(config.wilsonBaseline()) >= 0);
        boolean hardRisk = input.hardStop() || risk == null || risk.compareTo(config.riskLimit()) >= 0
                || "SELL".equals(normalizeAction(input.predictionAction()));
        String action;
        String category;
        if (hardRisk) {
            action = input.holding() ? "REDUCE" : "WATCH";
            category = input.holding() ? "HOLDING_RISK" : "CAUTIOUS";
        } else if (enoughEvidence && score.compareTo(config.recommendScore()) >= 0
                && quality.compareTo(config.minimumDataQuality()) >= 0) {
            action = "BUY";
            category = "RECOMMEND";
        } else {
            action = "WATCH";
            category = "CAUTIOUS";
        }
        String confidence = enoughEvidence ? "OOS_VALIDATED" : "LOW_SAMPLE";
        return new Decision(category, action, score, horizon.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                factor.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                strategy.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP), risk, confidence,
                enoughEvidence ? null : "LOW_SAMPLE");
    }

    private static BigDecimal normalize(BigDecimal value) {
        return DecisionEvidenceCalibrator.normalize(value);
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(new BigDecimal("100"));
    }

    private static String normalizeAction(String value) {
        return value == null ? "WATCH" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record Input(
            HorizonDecisionEvidence t1,
            HorizonDecisionEvidence t2,
            HorizonDecisionEvidence t3,
            BigDecimal factorSupportSignal,
            BigDecimal strategyValidationSignal,
            BigDecimal dataQuality,
            BigDecimal riskScore,
            boolean hardStop,
            String predictionAction,
            boolean holding,
            String unavailableReason
    ) {
    }

    public record Decision(
            String category,
            String action,
            BigDecimal systemScore,
            BigDecimal horizonSignalScore,
            BigDecimal factorSupportScore,
            BigDecimal strategyValidationScore,
            BigDecimal riskScore,
            String confidenceLevel,
            String unavailableReason
    ) {
        static Decision unavailable(String reason) {
            return new Decision("DATA_UNAVAILABLE", "WATCH", null, null, null, null, null,
                    "DATA_UNAVAILABLE", reason);
        }
    }
}
