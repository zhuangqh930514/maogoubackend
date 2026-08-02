package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.research.HistoricalFastStartPayloads;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.security.ResearchOperatorAuthorizer;
import com.maogou.stock.service.research.AiHistoricalFastStartService;
import com.maogou.stock.service.research.AiTrainingDatasetFreezeService;
import com.maogou.stock.service.research.HistoricalProviderPreflightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator-only API for previewing and controlling historical fast-start runs. */
@RestController
@RequestMapping("/api/ai/research-lab/historical-fast-start")
public class HistoricalFastStartController {

    private final AiHistoricalFastStartService service;
    private final ResearchOperatorAuthorizer authorizer;
    private final HistoricalProviderPreflightService providerPreflightService;
    private final AiTrainingDatasetFreezeService datasetFreezeService;

    public HistoricalFastStartController(
            AiHistoricalFastStartService service,
            ResearchOperatorAuthorizer authorizer
    ) {
        this(service, authorizer, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HistoricalFastStartController(
            AiHistoricalFastStartService service,
            ResearchOperatorAuthorizer authorizer,
            HistoricalProviderPreflightService providerPreflightService,
            AiTrainingDatasetFreezeService datasetFreezeService
    ) {
        this.service = service;
        this.authorizer = authorizer;
        this.providerPreflightService = providerPreflightService;
        this.datasetFreezeService = datasetFreezeService;
    }

    @PostMapping("/preview")
    public ApiResponse<HistoricalFastStartPayloads.PreviewResult> preview(
            @RequestBody HistoricalFastStartPayloads.PreviewRequest request
    ) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.preview(request, currentUserId()));
    }

    @PostMapping("/runs")
    public ApiResponse<HistoricalFastStartPayloads.RunView> create(
            @RequestBody HistoricalFastStartPayloads.CreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.create(request, idempotencyKey, currentUserId()));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<HistoricalFastStartPayloads.RunView> getRun(@PathVariable Long runId) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.getRun(runId, currentUserId()));
    }

    @GetMapping("/runs/{runId}/shards")
    public ApiResponse<HistoricalFastStartPayloads.PageResult<HistoricalFastStartPayloads.ShardView>> listShards(
            @PathVariable Long runId,
            HistoricalFastStartPayloads.ShardQuery query
    ) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.listShards(runId, query, currentUserId()));
    }

    @GetMapping("/runs/{runId}/issues")
    public ApiResponse<HistoricalFastStartPayloads.PageResult<HistoricalFastStartPayloads.IssueView>> listIssues(
            @PathVariable Long runId,
            HistoricalFastStartPayloads.IssueQuery query
    ) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.listIssues(runId, query, currentUserId()));
    }

    @GetMapping("/readiness/latest")
    public ApiResponse<HistoricalFastStartPayloads.ReadinessView> latestReadiness() {
        authorizer.requireOperator();
        return ApiResponse.ok(service.latestReadiness(currentUserId()));
    }

    @GetMapping("/runs/{runId}/readiness")
    public ApiResponse<HistoricalFastStartPayloads.ReadinessView> readiness(@PathVariable Long runId) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.validate(runId, currentUserId()));
    }

    @GetMapping("/provider-preflight")
    public ApiResponse<HistoricalProviderPreflightService.PreflightResult> providerPreflight(
            @RequestParam(required = false) String asOfTime,
            @RequestParam(required = false) String benchmarkSymbol
    ) {
        authorizer.requireOperator();
        if (providerPreflightService == null) {
            throw new IllegalStateException("历史 provider 预检服务未启用");
        }
        java.time.LocalDateTime cutoff = asOfTime == null || asOfTime.isBlank()
                ? java.time.LocalDateTime.now()
                : java.time.LocalDateTime.parse(asOfTime);
        return ApiResponse.ok(providerPreflightService.check(cutoff, benchmarkSymbol));
    }

    @PostMapping("/runs/{runId}/freeze-dataset")
    public ApiResponse<AiTrainingDatasetFreezeService.FreezeResult> freezeDataset(
            @PathVariable Long runId,
            @RequestParam(required = false) Long datasetId
    ) {
        authorizer.requireOperator();
        if (datasetFreezeService == null) {
            throw new IllegalStateException("训练数据集冻结服务未启用");
        }
        return ApiResponse.ok(datasetFreezeService.freeze(
                new AiTrainingDatasetFreezeService.FreezeRequest(
                        runId, datasetId, currentUserId(), java.time.LocalDateTime.now())));
    }

    @PostMapping("/runs/{runId}/pause")
    public ApiResponse<HistoricalFastStartPayloads.RunView> pause(@PathVariable Long runId) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.pause(runId, currentUserId()));
    }

    @PostMapping("/runs/{runId}/resume")
    public ApiResponse<HistoricalFastStartPayloads.RunView> resume(@PathVariable Long runId) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.resume(runId, currentUserId()));
    }

    @PostMapping("/runs/{runId}/retry-failed")
    public ApiResponse<HistoricalFastStartPayloads.RunView> retryFailed(@PathVariable Long runId) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.retryFailed(runId, currentUserId()));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<HistoricalFastStartPayloads.RunView> cancel(@PathVariable Long runId) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.cancel(runId, currentUserId()));
    }

    @PostMapping("/runs/{runId}/validate")
    public ApiResponse<HistoricalFastStartPayloads.ReadinessView> validate(@PathVariable Long runId) {
        authorizer.requireOperator();
        return ApiResponse.ok(service.validate(runId, currentUserId()));
    }

    private static Long currentUserId() {
        return AuthContext.currentUserId().orElseThrow(() ->
                new org.springframework.security.access.AccessDeniedException("请先登录"));
    }
}
