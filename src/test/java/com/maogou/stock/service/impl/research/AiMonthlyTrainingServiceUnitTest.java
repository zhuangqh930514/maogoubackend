package com.maogou.stock.service.impl.research;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AiMonthlyTrainingServiceUnitTest {

    @Test
    void convertsPercentagePointWilsonBoundsToUnitRatiosForCandidateWeights() {
        assertThat(AiMonthlyTrainingServiceImpl.normalizePercentagePoint(new BigDecimal("60.3774")))
                .isEqualByComparingTo("0.60377400");
        assertThat(AiMonthlyTrainingServiceImpl.normalizePercentagePoint(new BigDecimal("0.603774")))
                .isEqualByComparingTo("0.60377400");
        assertThat(AiMonthlyTrainingServiceImpl.normalizePercentagePoint(new BigDecimal("160")))
                .isEqualByComparingTo("1.00000000");
    }
}
