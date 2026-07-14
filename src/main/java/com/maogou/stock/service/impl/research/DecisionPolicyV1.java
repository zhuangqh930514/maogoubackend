package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.research.AiDailyDecisionPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

@Component
public class DecisionPolicyV1 implements AiDailyDecisionPolicy {

    public static final String VERSION = "DECISION/1.0.0";
    private static final BigDecimal H1_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal H2_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal H3_WEIGHT = new BigDecimal("0.50");
    private static final BigDecimal RECOMMEND_SCORE = new BigDecimal("70");
    private static final BigDecimal RECOMMEND_RISK = new BigDecimal("60");
    private static final BigDecimal MIN_RECOMMEND_QUALITY = new BigDecimal("0.90");
    private static final BigDecimal HIGH_RISK = new BigDecimal("70");
    private static final Set<String> RISK_ACTIONS = Set.of("REDUCE", "SELL");

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Decision decide(Input input) {
        if (input == null) {
            throw new IllegalArgumentException("每日决策输入不能为空");
        }
        String unavailableReason = unavailableReason(input);
        if (unavailableReason != null) {
            return new Decision("DATA_UNAVAILABLE", null, null, null, null, null, null,
                    null, null, null, "DATA_UNAVAILABLE", unavailableReason);
        }

        BigDecimal t1 = unit(input.t1Signal());
        BigDecimal t2 = unit(input.t2Signal());
        BigDecimal t3 = unit(input.t3Signal());
        BigDecimal factor = unit(input.factorOosReliability());
        BigDecimal strategy = unit(input.strategyOosValidation());
        BigDecimal quality = unit(input.dataQuality());
        BigDecimal risk = percent(input.riskScore());
        BigDecimal riskUnit = BigDecimal.ONE.subtract(risk.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP));
        BigDecimal horizon = t1.multiply(H1_WEIGHT)
                .add(t2.multiply(H2_WEIGHT))
                .add(t3.multiply(H3_WEIGHT));
        BigDecimal score = horizon.multiply(new BigDecimal("0.45"))
                .add(factor.multiply(new BigDecimal("0.20")))
                .add(strategy.multiply(new BigDecimal("0.15")))
                .add(quality.multiply(new BigDecimal("0.10")))
                .add(riskUnit.multiply(new BigDecimal("0.10")))
                .multiply(new BigDecimal("100"));

        String confidence = input.outOfSampleCount() >= 200 ? "OOS_VALIDATED" : "LOW_SAMPLE";
        String predictionAction = normalize(input.predictionAction());
        boolean hardRisk = input.hardStop() || risk.compareTo(HIGH_RISK) >= 0
                || RISK_ACTIONS.contains(predictionAction);
        String category;
        String action;
        if (hardRisk) {
            category = input.holding() ? "HOLDING_RISK" : "AVOID";
            action = input.hardStop() || "SELL".equals(predictionAction) ? "SELL" : "REDUCE";
        } else if (score.compareTo(RECOMMEND_SCORE) >= 0
                && risk.compareTo(RECOMMEND_RISK) < 0
                && quality.compareTo(MIN_RECOMMEND_QUALITY) >= 0
                && input.outOfSampleCount() >= 200) {
            category = "RECOMMEND";
            action = "BUY";
        } else {
            category = "CAUTIOUS";
            action = "WATCH";
        }
        return new Decision(
                category,
                scale(score),
                display(horizon),
                display(factor),
                display(strategy),
                display(quality),
                display(riskUnit),
                action,
                scale(risk),
                riskLevel(risk),
                confidence,
                null);
    }

    private static String unavailableReason(Input input) {
        if (input.unavailableReason() != null && !input.unavailableReason().isBlank()) {
            return input.unavailableReason();
        }
        if (input.t1Signal() == null || input.t2Signal() == null || input.t3Signal() == null) {
            return "MISSING_CORE_PREDICTION";
        }
        if (input.factorOosReliability() == null || input.strategyOosValidation() == null
                || input.dataQuality() == null || input.riskScore() == null) {
            return "MISSING_DECISION_COMPONENT";
        }
        return null;
    }

    private static BigDecimal unit(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static BigDecimal percent(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(new BigDecimal("100"));
    }

    private static BigDecimal display(BigDecimal unitValue) {
        return scale(unitValue.multiply(new BigDecimal("100")));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String normalize(String value) {
        return value == null ? "WATCH" : value.trim().toUpperCase();
    }

    private static String riskLevel(BigDecimal risk) {
        if (risk.compareTo(new BigDecimal("30")) < 0) {
            return "LOW";
        }
        if (risk.compareTo(new BigDecimal("60")) < 0) {
            return "MEDIUM";
        }
        return "HIGH";
    }
}
