package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiEvolutionDashboardResponse;
import com.maogou.stock.dto.ai.AiEvolutionReviewResponse;
import com.maogou.stock.dto.ai.AiFactorCenterResponse;
import com.maogou.stock.dto.ai.AiStrategyEvolutionResponse;
import com.maogou.stock.service.AiEvolutionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/evolution")
public class AiEvolutionController {

    private final AiEvolutionService aiEvolutionService;

    public AiEvolutionController(AiEvolutionService aiEvolutionService) {
        this.aiEvolutionService = aiEvolutionService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<AiEvolutionDashboardResponse> dashboard() {
        return ApiResponse.ok(aiEvolutionService.dashboard());
    }

    @GetMapping("/reviews")
    public ApiResponse<AiEvolutionReviewResponse> reviews() {
        return ApiResponse.ok(aiEvolutionService.reviews());
    }

    @PostMapping("/reviews/verify")
    public ApiResponse<AiEvolutionReviewResponse> verifyReviews() {
        return ApiResponse.ok(aiEvolutionService.verifyReviews());
    }

    @GetMapping("/factors")
    public ApiResponse<AiFactorCenterResponse> factors() {
        return ApiResponse.ok(aiEvolutionService.factors());
    }

    @PostMapping("/factors/refresh")
    public ApiResponse<AiFactorCenterResponse> refreshFactors() {
        return ApiResponse.ok(aiEvolutionService.refreshFactors());
    }

    @GetMapping("/strategies")
    public ApiResponse<AiStrategyEvolutionResponse> strategies() {
        return ApiResponse.ok(aiEvolutionService.strategies());
    }

    @PostMapping("/strategies/evolve")
    public ApiResponse<AiStrategyEvolutionResponse> evolveStrategy() {
        return ApiResponse.ok(aiEvolutionService.evolveStrategy());
    }

    @PostMapping("/strategies/{strategyId}/activate")
    public ApiResponse<AiStrategyEvolutionResponse> activateStrategy(@PathVariable Long strategyId) {
        return ApiResponse.ok(aiEvolutionService.activateStrategy(strategyId));
    }

    @PostMapping("/strategies/{strategyId}/rollback")
    public ApiResponse<AiStrategyEvolutionResponse> rollbackStrategy(@PathVariable Long strategyId) {
        return ApiResponse.ok(aiEvolutionService.activateStrategy(strategyId));
    }
}
