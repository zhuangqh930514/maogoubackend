package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.research.HorizonDecisionEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionPolicyShadowTest {

    private final DecisionPolicyShadow policy = new DecisionPolicyShadow();

    @Test
    void neutralEvidenceStaysNearFiftyInsteadOfMultiplyingToTwelvePointFive() {
        DecisionPolicyShadow.Decision decision = policy.decide(input(
                evidence(BigDecimal.valueOf(0.50)),
                evidence(BigDecimal.valueOf(0.50)),
                evidence(BigDecimal.valueOf(0.50)),
                BigDecimal.valueOf(0.50), BigDecimal.valueOf(0.50), BigDecimal.ONE));

        assertEquals(0, new BigDecimal("50.0000").compareTo(decision.systemScore()));
        assertEquals("WATCH", decision.action());
    }

    @Test
    void strongOutOfSampleEvidenceCanReachBuyButRequiresRiskAndQualityGates() {
        DecisionPolicyShadow.Decision decision = policy.decide(input(
                evidence(BigDecimal.valueOf(0.90)),
                evidence(BigDecimal.valueOf(0.85)),
                evidence(BigDecimal.valueOf(0.80)),
                BigDecimal.valueOf(0.80), BigDecimal.valueOf(0.70), BigDecimal.ONE));

        assertEquals("BUY", decision.action());
        assertEquals("RECOMMEND", decision.category());
        org.junit.jupiter.api.Assertions.assertTrue(decision.systemScore().compareTo(BigDecimal.valueOf(70)) >= 0);
    }

    @Test
    void unavailableCoreDataCannotBecomeSell() {
        DecisionPolicyShadow.Decision decision = policy.decide(new DecisionPolicyShadow.Input(
                null, null, null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, false, "SELL", false, "MISSING_CURRENT_SAMPLE"));

        assertEquals("DATA_UNAVAILABLE", decision.category());
        assertEquals("WATCH", decision.action());
    }

    private static DecisionPolicyShadow.Input input(
            HorizonDecisionEvidence t1,
            HorizonDecisionEvidence t2,
            HorizonDecisionEvidence t3,
            BigDecimal factor,
            BigDecimal strategy,
            BigDecimal quality
    ) {
        return new DecisionPolicyShadow.Input(t1, t2, t3, factor, strategy, quality,
                BigDecimal.valueOf(20), false, "WATCH", false, null);
    }

    private static HorizonDecisionEvidence evidence(BigDecimal signal) {
        return new HorizonDecisionEvidence(1, signal, 200, BigDecimal.valueOf(0.65),
                BigDecimal.valueOf(0.65), "STOCK");
    }
}
