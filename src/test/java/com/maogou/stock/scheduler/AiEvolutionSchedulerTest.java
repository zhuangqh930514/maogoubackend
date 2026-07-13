package com.maogou.stock.scheduler;

import com.maogou.stock.service.v2.AiEvolutionAutomationService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiEvolutionSchedulerTest {

    @Test
    void delegatesWeeklyAndMonthlyTriggersToTheV2AutomationService() {
        AiEvolutionAutomationService service = mock(AiEvolutionAutomationService.class);
        AiEvolutionScheduler scheduler = new AiEvolutionScheduler(service);

        scheduler.runWeeklyEvolution();
        scheduler.runMonthlyTraining();

        verify(service).runWeeklyForEnabledUsers();
        verify(service).runMonthlyForEnabledUsers();
    }
}
