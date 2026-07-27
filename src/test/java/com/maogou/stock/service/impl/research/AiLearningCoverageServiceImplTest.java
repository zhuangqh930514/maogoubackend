package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiLearningCoverageDaily;
import com.maogou.stock.mapper.research.AiLearningCoverageDailyMapper;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLearningCoverageServiceImplTest {

    @Test
    void writesZeroPlanCountersInsteadOfBindingNullToNotNullColumns() {
        AiLearningCoverageDailyMapper mapper = mock(AiLearningCoverageDailyMapper.class);
        when(mapper.countEligibleDuePredictions(any(), anyInt(), anyInt(), anyString())).thenReturn(12L);
        when(mapper.countEvaluatedDuePredictions(any(), anyInt(), anyInt(), anyString(), anyString())).thenReturn(9L);
        when(mapper.upsert(any())).thenReturn(1);

        new AiLearningCoverageServiceImpl(mapper).recordDueEvaluation(
                237L,
                LocalDate.of(2026, 7, 27),
                new AiLabelVerificationCoordinator.VerificationResult(12, 9, 3, List.of("行情提供方暂不可用"), "proof"),
                LocalDateTime.of(2026, 7, 27, 16, 2, 28));

        ArgumentCaptor<AiLearningCoverageDaily> values = ArgumentCaptor.forClass(AiLearningCoverageDaily.class);
        verify(mapper, times(3)).upsert(values.capture());
        assertThat(values.getAllValues()).allSatisfy(value -> {
            assertThat(value.planDueCount).isZero();
            assertThat(value.planTriggerCheckedCount).isZero();
            assertThat(value.planOutcomeEvaluatedCount).isZero();
        });
    }
}
