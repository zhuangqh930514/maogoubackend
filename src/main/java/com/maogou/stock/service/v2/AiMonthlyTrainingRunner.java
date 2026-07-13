package com.maogou.stock.service.v2;

import java.time.LocalDateTime;

public interface AiMonthlyTrainingRunner {

    AiEvolutionAutomationService.CycleResult run(Long userId, LocalDateTime triggeredAt);
}
