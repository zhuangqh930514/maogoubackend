package com.maogou.stock.service.research;

import java.time.LocalDateTime;

public interface AiWeeklyEvolutionRunner {

    AiResearchCycleResult run(Long actorUserId, LocalDateTime triggeredAt);
}
