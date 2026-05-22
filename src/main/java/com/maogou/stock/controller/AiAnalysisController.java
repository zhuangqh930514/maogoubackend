package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.RunAnalysisRequest;
import com.maogou.stock.service.AiAnalysisService;
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

    @PostMapping("/analyze")
    public ApiResponse<AiAnalysisReportResponse> analyze(@RequestBody RunAnalysisRequest request) {
        return ApiResponse.ok(aiAnalysisService.analyzeStock(request.code(), request.forceRefresh()));
    }

    @PostMapping("/analyze-watchlist")
    public ApiResponse<Void> analyzeWatchlist() {
        aiAnalysisService.analyzeWatchlist();
        return ApiResponse.ok(null);
    }
}
