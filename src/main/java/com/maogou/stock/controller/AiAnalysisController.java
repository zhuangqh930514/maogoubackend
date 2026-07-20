package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportPageResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportSummaryResponse;
import com.maogou.stock.dto.ai.BatchAiAnalysisReportDeleteRequest;
import com.maogou.stock.dto.ai.RunAnalysisRequest;
import com.maogou.stock.dto.ai.RunWatchlistAnalysisRequest;
import com.maogou.stock.dto.ai.WatchlistAnalysisResult;
import com.maogou.stock.service.AiAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public AiAnalysisController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @GetMapping("/reports")
    public ApiResponse<List<AiAnalysisReportSummaryResponse>> reports(@RequestParam(required = false) String code) {
        return ApiResponse.ok(aiAnalysisService.listReports(code));
    }

    @GetMapping("/reports/latest")
    public ApiResponse<AiAnalysisReportResponse> latestReport(@RequestParam(required = false) String code) {
        return ApiResponse.ok(aiAnalysisService.latestReport(code));
    }

    @GetMapping("/reports/{reportId}")
    public ApiResponse<AiAnalysisReportResponse> report(@PathVariable Long reportId) {
        return ApiResponse.ok(aiAnalysisService.report(reportId));
    }

    @GetMapping("/reports/page")
    public ApiResponse<AiAnalysisReportPageResponse> reportPage(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "ALL") String filter
    ) {
        return ApiResponse.ok(aiAnalysisService.pageReports(code, date, page, pageSize, filter));
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
    public ApiResponse<WatchlistAnalysisResult> analyzeWatchlist(@RequestBody(required = false) RunWatchlistAnalysisRequest request) {
        return ApiResponse.ok(aiAnalysisService.analyzeWatchlist(request == null ? null : request.promptTemplateId()));
    }
}
