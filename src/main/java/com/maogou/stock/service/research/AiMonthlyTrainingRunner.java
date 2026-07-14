package com.maogou.stock.service.research;

import java.time.LocalDateTime;

public interface AiMonthlyTrainingRunner {

    AiEvolutionAutomationService.CycleResult run(Long userId, LocalDateTime triggeredAt);
}
