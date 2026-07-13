package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;
import com.maogou.stock.service.AiResearchDailyReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/research-daily-reports")
public class AiResearchDailyReportController {

    private final AiResearchDailyReportService reportService;

    public AiResearchDailyReportController(AiResearchDailyReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/latest")
    public ApiResponse<AiResearchDailyReportService.ReportView> latest() {
        return ApiResponse.ok(reportService.latest());
    }

    @GetMapping
    public ApiResponse<List<AiResearchDailyReportPayloads.ReportListItem>> list(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(reportService.list(limit));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<AiResearchDailyReportService.ReportView> detail(@PathVariable Long reportId) {
        return ApiResponse.ok(reportService.detail(reportId));
    }

    @PostMapping("/rebuild")
    public ApiResponse<AiResearchDailyReportService.ReportView> rebuild() {
        return ApiResponse.ok(reportService.rebuildToday());
    }
}
