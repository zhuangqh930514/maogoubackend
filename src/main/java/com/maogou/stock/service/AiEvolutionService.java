package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiEvolutionDashboardResponse;
import com.maogou.stock.dto.ai.AiEvolutionReviewResponse;
import com.maogou.stock.dto.ai.AiFactorCenterResponse;
import com.maogou.stock.dto.ai.AiStrategyEvolutionResponse;

public interface AiEvolutionService {
    AiEvolutionDashboardResponse dashboard();

    AiEvolutionReviewResponse reviews();

    AiEvolutionReviewResponse verifyReviews();

    AiFactorCenterResponse factors();

    AiFactorCenterResponse refreshFactors();

    AiStrategyEvolutionResponse strategies();

    AiStrategyEvolutionResponse evolveStrategy();

    AiStrategyEvolutionResponse activateStrategy(Long strategyId);
}
