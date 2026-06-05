package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiLearningPayloads;
import com.maogou.stock.service.AiLearningService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/learning")
public class AiLearningController {

    private final AiLearningService aiLearningService;

    public AiLearningController(AiLearningService aiLearningService) {
        this.aiLearningService = aiLearningService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<AiLearningPayloads.LearningDashboardResponse> dashboard() {
        return ApiResponse.ok(aiLearningService.dashboard());
    }

    @GetMapping("/samples")
    public ApiResponse<AiLearningPayloads.SampleCenterResponse> samples(
            @RequestParam(required = false) String stockCode,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(aiLearningService.samples(stockCode, limit));
    }

    @GetMapping("/samples/{sampleId}")
    public ApiResponse<AiLearningPayloads.SampleDetailResponse> sampleDetail(@PathVariable Long sampleId) {
        return ApiResponse.ok(aiLearningService.sampleDetail(sampleId));
    }

    @PostMapping("/samples/build-watchlist")
    public ApiResponse<AiLearningPayloads.SampleCenterResponse> buildWatchlistSamples(@RequestBody(required = false) AiLearningPayloads.BuildSamplesRequest request) {
        return ApiResponse.ok(aiLearningService.buildWatchlistSamples(
                request == null ? null : request.universeCode(),
                request == null ? null : request.samplePhase()
        ));
    }

    @PostMapping("/samples/{sampleId}/recompute-factors")
    public ApiResponse<AiLearningPayloads.SampleDetailResponse> recomputeSampleFactors(@PathVariable Long sampleId) {
        return ApiResponse.ok(aiLearningService.recomputeSampleFactors(sampleId));
    }

    @GetMapping("/factor-factory")
    public ApiResponse<AiLearningPayloads.FactorFactoryResponse> factorFactory() {
        return ApiResponse.ok(aiLearningService.factorFactory());
    }

    @GetMapping("/predictions")
    public ApiResponse<AiLearningPayloads.PredictionCenterResponse> predictions(@RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(aiLearningService.predictions(limit));
    }

    @PostMapping("/predictions/rank-universe")
    public ApiResponse<AiLearningPayloads.PredictionRankResponse> rankUniverse(@RequestBody(required = false) AiLearningPayloads.RankUniverseRequest request) {
        return ApiResponse.ok(aiLearningService.rankUniverse(
                request == null ? null : request.universeCode(),
                request == null ? null : request.horizonDays(),
                request == null ? null : request.topK()
        ));
    }

    @GetMapping("/labels")
    public ApiResponse<AiLearningPayloads.LabelCenterResponse> labels(@RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(aiLearningService.labels(limit));
    }

    @PostMapping("/labels/verify")
    public ApiResponse<AiLearningPayloads.LabelCenterResponse> verifyLabels() {
        return ApiResponse.ok(aiLearningService.verifyLabels());
    }

    @GetMapping("/experiments")
    public ApiResponse<AiLearningPayloads.ExperimentCenterResponse> experiments() {
        return ApiResponse.ok(aiLearningService.experiments());
    }

    @PostMapping("/experiments/run")
    public ApiResponse<AiLearningPayloads.ExperimentCenterResponse> runExperiment(@RequestBody(required = false) AiLearningPayloads.RunExperimentRequest request) {
        return ApiResponse.ok(aiLearningService.runExperiment(
                request == null ? null : request.title(),
                request == null ? null : request.universeCode()
        ));
    }

    @GetMapping("/backtests")
    public ApiResponse<AiLearningPayloads.BacktestCenterResponse> backtests() {
        return ApiResponse.ok(aiLearningService.backtests());
    }

    @GetMapping("/backtests/{runId}")
    public ApiResponse<AiLearningPayloads.BacktestDetailResponse> backtestDetail(@PathVariable Long runId) {
        return ApiResponse.ok(aiLearningService.backtestDetail(runId));
    }

    @PostMapping("/backtests/run")
    public ApiResponse<AiLearningPayloads.BacktestDetailResponse> runBacktest(@RequestBody(required = false) AiLearningPayloads.RunBacktestRequest request) {
        return ApiResponse.ok(aiLearningService.runBacktest(
                request == null ? null : request.title(),
                request == null ? null : request.universeCode(),
                request == null ? null : request.horizonDays(),
                request == null ? null : request.topK()
        ));
    }

    @GetMapping("/model-evals")
    public ApiResponse<AiLearningPayloads.ModelEvalCenterResponse> modelEvals() {
        return ApiResponse.ok(aiLearningService.modelEvals());
    }

    @PostMapping("/model-evals/run")
    public ApiResponse<AiLearningPayloads.ModelEvalCenterResponse> runModelEval(@RequestBody(required = false) AiLearningPayloads.RunModelEvalRequest request) {
        return ApiResponse.ok(aiLearningService.runModelEval(
                request == null ? null : request.evalType(),
                request == null ? null : request.sampleCount()
        ));
    }
}
