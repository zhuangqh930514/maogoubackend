package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.service.AiResearchDailyReportIssueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/research-daily-reports")
public class AiResearchDailyReportIssueController {
    private final AiResearchDailyReportIssueService issueService;

    public AiResearchDailyReportIssueController(AiResearchDailyReportIssueService issueService) {
        this.issueService = issueService;
    }

    @GetMapping("/{reportId}/issues")
    public ApiResponse<AiResearchDailyReportIssueService.IssuePage> page(
            @PathVariable Long reportId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return ApiResponse.ok(issueService.page(reportId, page, pageSize));
    }
}
