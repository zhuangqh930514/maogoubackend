package com.maogou.stock.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AiDailyInsightScoringTest {

    @Test
    void recommendsBuyWhenPredictionDecisionQualityAndHistoryAreStrong() {
        AiDailyInsightScoring.Decision decision = AiDailyInsightScoring.classify(new AiDailyInsightScoring.Input(
                new BigDecimal("78"),
                new BigDecimal("32"),
                new BigDecimal("86"),
                new BigDecimal("90"),
                "BUY",
                new BigDecimal("0.82"),
                new BigDecimal("68"),
                16,
                BigDecimal.ZERO,
                0
        ));

        assertThat(decision.finalAction()).isEqualTo("BUY");
        assertThat(decision.actionBucket()).isEqualTo("RECOMMEND");
        assertThat(decision.confidenceLevel()).isEqualTo("READY");
        assertThat(decision.compositeScore()).isEqualByComparingTo("78.50");
    }

    @Test
    void watchesWhenDataQualityIsTooLow() {
        AiDailyInsightScoring.Decision decision = AiDailyInsightScoring.classify(new AiDailyInsightScoring.Input(
                new BigDecimal("88"),
                new BigDecimal("20"),
                new BigDecimal("55"),
                new BigDecimal("85"),
                "BUY",
                new BigDecimal("0.90"),
                new BigDecimal("72"),
                20,
                BigDecimal.ZERO,
                0
        ));

        assertThat(decision.finalAction()).isEqualTo("UNAVAILABLE");
        assertThat(decision.actionBucket()).isEqualTo("WATCH");
        assertThat(decision.confidenceLevel()).isEqualTo("DATA_UNAVAILABLE");
    }

    @Test
    void missingStructuredDecisionCannotEnterRecommendationBucket() {
        AiDailyInsightScoring.Decision decision = AiDailyInsightScoring.classify(new AiDailyInsightScoring.Input(
                new BigDecimal("90"),
                new BigDecimal("18"),
                new BigDecimal("92"),
                new BigDecimal("95"),
                "",
                BigDecimal.ZERO,
                new BigDecimal("80"),
                30,
                BigDecimal.ZERO,
                0
        ));

        assertThat(decision.finalAction()).isEqualTo("WATCH");
        assertThat(decision.actionBucket()).isEqualTo("WATCH");
        assertThat(decision.confidenceLevel()).isEqualTo("AI_DECISION_MISSING");
    }

    @Test
    void usesFactorHistoryWhenStockHistoryIsInsufficient() {
        AiDailyInsightScoring.Decision decision = AiDailyInsightScoring.classify(new AiDailyInsightScoring.Input(
                new BigDecimal("76"),
                new BigDecimal("36"),
                new BigDecimal("84"),
                new BigDecimal("88"),
                "HOLD",
                new BigDecimal("0.74"),
                new BigDecimal("0"),
                2,
                new BigDecimal("66"),
                18
        ));

        assertThat(decision.finalAction()).isEqualTo("HOLD");
        assertThat(decision.actionBucket()).isEqualTo("RECOMMEND");
        assertThat(decision.confidenceLevel()).isEqualTo("FACTOR_PROXY");
    }

    @Test
    void marksLowSampleAsWatchWhenNoHistoryCanSupportTheCall() {
        AiDailyInsightScoring.Decision decision = AiDailyInsightScoring.classify(new AiDailyInsightScoring.Input(
                new BigDecimal("82"),
                new BigDecimal("30"),
                new BigDecimal("85"),
                new BigDecimal("90"),
                "BUY",
                new BigDecimal("0.85"),
                new BigDecimal("0"),
                3,
                BigDecimal.ZERO,
                4
        ));

        assertThat(decision.finalAction()).isEqualTo("WATCH");
        assertThat(decision.actionBucket()).isEqualTo("WATCH");
        assertThat(decision.confidenceLevel()).isEqualTo("LOW_SAMPLE");
    }

    @Test
    void watchesWhenHistoricalHitRateIsPoorWithEnoughSamples() {
        AiDailyInsightScoring.Decision decision = AiDailyInsightScoring.classify(new AiDailyInsightScoring.Input(
                new BigDecimal("74"),
                new BigDecimal("42"),
                new BigDecimal("82"),
                new BigDecimal("86"),
                "BUY",
                new BigDecimal("0.78"),
                new BigDecimal("38"),
                15,
                BigDecimal.ZERO,
                0
        ));

        assertThat(decision.finalAction()).isEqualTo("WATCH");
        assertThat(decision.actionBucket()).isEqualTo("WATCH");
        assertThat(decision.confidenceLevel()).isEqualTo("HISTORY_WEAK");
    }

    @Test
    void keepsCurrentReduceDecisionInAvoidBucketWhenHistoryIsWeak() {
        AiDailyInsightScoring.Decision decision = AiDailyInsightScoring.classify(new AiDailyInsightScoring.Input(
                new BigDecimal("52"),
                new BigDecimal("48"),
                new BigDecimal("82"),
                new BigDecimal("86"),
                "REDUCE",
                new BigDecimal("0.74"),
                new BigDecimal("38"),
                15,
                BigDecimal.ZERO,
                0
        ));

        assertThat(decision.finalAction()).isEqualTo("REDUCE");
        assertThat(decision.actionBucket()).isEqualTo("AVOID");
        assertThat(decision.confidenceLevel()).isEqualTo("HISTORY_WEAK");
    }
}
