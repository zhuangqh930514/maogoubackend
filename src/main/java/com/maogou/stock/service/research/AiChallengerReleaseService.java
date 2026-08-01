package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiStrategyRelease;

import java.time.LocalDateTime;

public interface AiChallengerReleaseService {

    AiStrategyRelease createFromValidatedModel(Long modelId, LocalDateTime now);
}
