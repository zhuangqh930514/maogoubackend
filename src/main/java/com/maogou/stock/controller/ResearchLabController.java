package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.research.AiResearchLabQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/research-lab")
public class ResearchLabController {

    private final AiResearchLabQueryService queryService;

    public ResearchLabController(AiResearchLabQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/overview")
    public ApiResponse<ResearchLabPayloads.Overview> overview() {
        return ApiResponse.ok(queryService.overview());
    }

    @GetMapping("/universe")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> universe(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.universe(filter));
    }

    @GetMapping("/data-batches")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> dataBatches(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.dataBatches(filter));
    }

    @GetMapping("/source-health")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> sourceHealth(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.sourceHealth(filter));
    }

    @GetMapping("/samples")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> samples(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.samples(filter));
    }

    @GetMapping("/samples/{id}")
    public ApiResponse<ResearchLabPayloads.Detail> sample(@PathVariable Long id) {
        return ApiResponse.ok(queryService.sample(id));
    }

    @GetMapping("/predictions")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> predictions(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.predictions(filter));
    }

    @GetMapping("/labels")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> labels(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.labels(filter));
    }

    @GetMapping("/prediction-evaluations")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> predictionEvaluations(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.predictionEvaluations(filter));
    }

    @GetMapping("/factors")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> factors(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.factors(filter));
    }

    @GetMapping("/factor-performance")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> factorPerformance(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.factorPerformance(filter));
    }

    @GetMapping("/datasets")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> datasets(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.datasets(filter));
    }

    @GetMapping("/datasets/{id}")
    public ApiResponse<ResearchLabPayloads.Detail> dataset(@PathVariable Long id) {
        return ApiResponse.ok(queryService.dataset(id));
    }

    @GetMapping("/models")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> models(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.models(filter));
    }

    @GetMapping("/models/{id}")
    public ApiResponse<ResearchLabPayloads.Detail> model(@PathVariable Long id) {
        return ApiResponse.ok(queryService.model(id));
    }

    @GetMapping("/walk-forward")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> walkForward(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.walkForward(filter));
    }

    @GetMapping("/walk-forward/{id}")
    public ApiResponse<ResearchLabPayloads.Detail> walkForward(@PathVariable Long id) {
        return ApiResponse.ok(queryService.walkForward(id));
    }

    @GetMapping("/backtests")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> backtests(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.backtests(filter));
    }

    @GetMapping("/backtests/{id}")
    public ApiResponse<ResearchLabPayloads.Detail> backtest(@PathVariable Long id) {
        return ApiResponse.ok(queryService.backtest(id));
    }

    @GetMapping("/strategies")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> strategies(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.strategies(filter));
    }

    @GetMapping("/strategies/{id}")
    public ApiResponse<ResearchLabPayloads.Detail> strategy(@PathVariable Long id) {
        return ApiResponse.ok(queryService.strategy(id));
    }

    @GetMapping("/shadow-evaluations")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> shadowEvaluations(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.shadowEvaluations(filter));
    }

    @GetMapping("/governance-events")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> governanceEvents(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.governanceEvents(filter));
    }

    @GetMapping("/pipeline-runs")
    public ApiResponse<ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem>> pipelineRuns(
            @ModelAttribute ResearchLabPayloads.QueryFilter filter) {
        return ApiResponse.ok(queryService.pipelineRuns(filter));
    }

    @GetMapping("/pipeline-runs/{id}")
    public ApiResponse<ResearchLabPayloads.Detail> pipelineRun(@PathVariable Long id) {
        Long userId = AuthContext.currentUserId().orElseThrow(() ->
                new org.springframework.security.access.AccessDeniedException("请先登录"));
        return ApiResponse.ok(queryService.pipelineRun(id, userId));
    }
}
