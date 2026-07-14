package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.research.AiDailyDecisionPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPolicyV1Test {

    private final DecisionPolicyV1 policy = new DecisionPolicyV1();

    @Test
    void usesTheVersionedFiveComponentFormulaAndIgnoresLlmConfidence() {
        AiDailyDecisionPolicy.Input input = input(400, false, false, "BUY", false, null);

        AiDailyDecisionPolicy.Decision lowLlm = policy.decide(input.withLlmConfidence(new BigDecimal("0.10")));
        AiDailyDecisionPolicy.Decision highLlm = policy.decide(input.withLlmConfidence(new BigDecimal("0.99")));

        assertThat(lowLlm.horizonSignalScore()).isEqualByComparingTo("77.0000");
        assertThat(lowLlm.systemScore()).isEqualByComparingTo("74.1500");
        assertThat(highLlm.systemScore()).isEqualByComparingTo(lowLlm.systemScore());
        assertThat(lowLlm.category()).isEqualTo("RECOMMEND");
        assertThat(lowLlm.finalAction()).isEqualTo("BUY");
        assertThat(policy.version()).isEqualTo("DECISION/1.0.0");
    }

    @Test
    void capsAHighScoreAtCautiousUntilTwoHundredOosEvaluationsExist() {
        AiDailyDecisionPolicy.Decision decision = policy.decide(
                input(199, false, false, "BUY", false, null));

        assertThat(decision.systemScore()).isGreaterThanOrEqualTo(new BigDecimal("70"));
        assertThat(decision.category()).isEqualTo("CAUTIOUS");
        assertThat(decision.finalAction()).isEqualTo("WATCH");
        assertThat(decision.confidenceLevel()).isEqualTo("LOW_SAMPLE");
    }

    @Test
    void riskAndHardStopsOverrideAHighCompositeScore() {
        AiDailyDecisionPolicy.Input highRisk = input(400, false, false, "BUY", true, null)
                .withRiskScore(new BigDecimal("75"));
        AiDailyDecisionPolicy.Decision riskDecision = policy.decide(highRisk);
        AiDailyDecisionPolicy.Decision hardStopDecision = policy.decide(
                input(400, true, false, "BUY", false, null));

        assertThat(riskDecision.category()).isEqualTo("HOLDING_RISK");
        assertThat(riskDecision.finalAction()).isEqualTo("REDUCE");
        assertThat(hardStopDecision.category()).isEqualTo("AVOID");
        assertThat(hardStopDecision.finalAction()).isEqualTo("SELL");
    }

    @Test
    void missingCoreInputsAreExplicitlyUnavailableWithoutSyntheticScores() {
        AiDailyDecisionPolicy.Decision decision = policy.decide(
                input(400, false, false, "BUY", false, "MISSING_T2_PREDICTION"));

        assertThat(decision.category()).isEqualTo("DATA_UNAVAILABLE");
        assertThat(decision.systemScore()).isNull();
        assertThat(decision.finalAction()).isNull();
        assertThat(decision.riskScore()).isNull();
        assertThat(decision.unavailableReason()).isEqualTo("MISSING_T2_PREDICTION");
    }

    private static AiDailyDecisionPolicy.Input input(
            int oosCount,
            boolean hardStop,
            boolean reduceSignal,
            String action,
            boolean holding,
            String unavailableReason
    ) {
        return new AiDailyDecisionPolicy.Input(
                new BigDecimal("0.70"),
                new BigDecimal("0.80"),
                new BigDecimal("0.78"),
                new BigDecimal("0.65"),
                new BigDecimal("0.70"),
                new BigDecimal("0.95"),
                new BigDecimal("35"),
                oosCount,
                hardStop,
                reduceSignal ? "REDUCE" : action,
                holding,
                unavailableReason,
                BigDecimal.ZERO);
    }
}
