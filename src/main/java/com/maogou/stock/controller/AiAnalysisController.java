package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.BatchAiAnalysisReportDeleteRequest;
import com.maogou.stock.dto.ai.RunAnalysisRequest;
import com.maogou.stock.dto.ai.RunWatchlistAnalysisRequest;
import com.maogou.stock.service.AiAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public AiAnalysisController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @GetMapping("/reports")
    public ApiResponse<List<AiAnalysisReportResponse>> reports(@RequestParam(required = false) String code) {
        return ApiResponse.ok(aiAnalysisService.listReports(code));
    }

    @PostMapping("/reports/batch-delete")
    public ApiResponse<Void> removeReports(@RequestBody @Valid BatchAiAnalysisReportDeleteRequest request) {
        aiAnalysisService.removeReports(request.ids());
        return ApiResponse.ok(null);
    }

    @PostMapping("/analyze")
    public ApiResponse<AiAnalysisReportResponse> analyze(@RequestBody RunAnalysisRequest request) {
        return ApiResponse.ok(aiAnalysisService.analyzeStock(request.code(), request.forceRefresh(), request.promptTemplateId(), request.targetReportId()));
    }

    @PostMapping("/analyze-watchlist")
    public ApiResponse<Void> analyzeWatchlist(@RequestBody(required = false) RunWatchlistAnalysisRequest request) {
        aiAnalysisService.analyzeWatchlist(request == null ? null : request.promptTemplateId());
        return ApiResponse.ok(null);
    }
}
