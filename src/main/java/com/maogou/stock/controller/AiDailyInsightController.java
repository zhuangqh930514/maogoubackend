package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiDailyInsightPayloads;
import com.maogou.stock.service.AiDailyInsightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/daily-insight")
public class AiDailyInsightController {

    private final AiDailyInsightService dailyInsightService;

    public AiDailyInsightController(AiDailyInsightService dailyInsightService) {
        this.dailyInsightService = dailyInsightService;
    }

    @GetMapping("/today")
    public ApiResponse<AiDailyInsightPayloads.DailyInsightResponse> today() {
        return ApiResponse.ok(dailyInsightService.today());
    }

    @PostMapping("/rebuild")
    public ApiResponse<AiDailyInsightPayloads.DailyInsightResponse> rebuild() {
        return ApiResponse.ok(dailyInsightService.rebuildToday());
    }
}
