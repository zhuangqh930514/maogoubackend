package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.research.ResearchOperationsOverviewPayloads;
import com.maogou.stock.security.ResearchOperatorAuthorizer;
import com.maogou.stock.service.research.AiResearchOperationsOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global operational evidence must never be exposed through normal user research endpoints. */
@RestController
@RequestMapping("/api/ai/research-lab")
public class ResearchOperationsOverviewController {

    private final AiResearchOperationsOverviewService overviewService;
    private final ResearchOperatorAuthorizer authorizer;

    public ResearchOperationsOverviewController(
            AiResearchOperationsOverviewService overviewService,
            ResearchOperatorAuthorizer authorizer
    ) {
        this.overviewService = overviewService;
        this.authorizer = authorizer;
    }

    @GetMapping("/operations-overview")
    public ApiResponse<ResearchOperationsOverviewPayloads.Overview> overview(
            @RequestParam(required = false) Integer windowDays
    ) {
        authorizer.requireOperator();
        return ApiResponse.ok(overviewService.overview(windowDays));
    }
}
