package com.maogou.stock.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiDailyInsightFallbackPolicyTest {

    @Test
    void v2PipelineResultsNeverFallBackToStaleLegacyPredictions() {
        assertThat(AiDailyInsightServiceImpl.shouldUseLegacyFallback("SUCCESS")).isFalse();
        assertThat(AiDailyInsightServiceImpl.shouldUseLegacyFallback("PARTIAL_SUCCESS")).isFalse();
        assertThat(AiDailyInsightServiceImpl.shouldUseLegacyFallback("FAILED")).isFalse();
        assertThat(AiDailyInsightServiceImpl.shouldUseLegacyFallback("MANUAL")).isTrue();
    }
}
