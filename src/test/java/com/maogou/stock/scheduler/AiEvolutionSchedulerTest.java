package com.maogou.stock.scheduler;

import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.service.research.AiResearchOperationsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEvolutionSchedulerTest {

    @Test
    void weeklyAndMonthlySchedulesSubmitOneGlobalResearchRunEach() {
        AiResearchOperationsService service = mock(AiResearchOperationsService.class);
        when(service.runWeekly(isNull(), any())).thenReturn(
                new ResearchLabPayloads.ActionAccepted(91L, "PENDING"));
        when(service.runTraining(isNull(), any())).thenReturn(
                new ResearchLabPayloads.ActionAccepted(92L, "PENDING"));
        AiEvolutionScheduler scheduler = new AiEvolutionScheduler(service);

        scheduler.runWeeklyEvolution();
        scheduler.runMonthlyTraining();

        ArgumentCaptor<ResearchLabPayloads.ActionRequest> weekly =
                ArgumentCaptor.forClass(ResearchLabPayloads.ActionRequest.class);
        ArgumentCaptor<ResearchLabPayloads.ActionRequest> monthly =
                ArgumentCaptor.forClass(ResearchLabPayloads.ActionRequest.class);
        verify(service).runWeekly(isNull(), weekly.capture());
        verify(service).runTraining(isNull(), monthly.capture());
        assertThat(weekly.getValue().idempotencyKey()).startsWith("SCHEDULED:GLOBAL_WEEKLY:");
        assertThat(monthly.getValue().idempotencyKey()).startsWith("SCHEDULED:GLOBAL_MONTHLY:");
        assertThat(weekly.getValue().userId()).isNull();
        assertThat(monthly.getValue().userId()).isNull();
    }
}
