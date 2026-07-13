package com.maogou.stock.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalCallTransactionBoundaryTest {

    @Test
    void aiModelAndMarketCallsAreNotWrappedInMethodTransactions() throws Exception {
        Method analyzeStock = AiAnalysisServiceImpl.class.getMethod(
                "analyzeStock", String.class, boolean.class, Long.class, Long.class);
        Method buildSamples = AiLearningServiceImpl.class.getMethod(
                "buildWatchlistSamples", String.class, String.class);
        Method recomputeFactors = AiLearningServiceImpl.class.getMethod(
                "recomputeSampleFactors", Long.class);

        assertThat(analyzeStock.getAnnotation(Transactional.class)).isNull();
        assertThat(buildSamples.getAnnotation(Transactional.class)).isNull();
        assertThat(recomputeFactors.getAnnotation(Transactional.class)).isNull();
    }
}
