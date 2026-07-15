package com.maogou.stock.service.research;

import java.time.LocalDateTime;

public interface AiMonthlyTrainingRunner {

    AiResearchCycleResult run(Long actorUserId, LocalDateTime triggeredAt);
}
