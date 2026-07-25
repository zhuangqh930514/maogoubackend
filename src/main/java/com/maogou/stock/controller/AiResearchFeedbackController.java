package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiResearchFeedbackPayload;
import com.maogou.stock.service.AiResearchFeedbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/research-daily-reports/{reportId}/feedback")
public class AiResearchFeedbackController {

    private final AiResearchFeedbackService feedbackService;

    public AiResearchFeedbackController(AiResearchFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public ApiResponse<List<AiResearchFeedbackPayload.Item>> list(@PathVariable Long reportId) {
        return ApiResponse.ok(feedbackService.list(reportId));
    }

    @PostMapping
    public ApiResponse<AiResearchFeedbackPayload.Item> submit(
            @PathVariable Long reportId,
            @RequestBody AiResearchFeedbackPayload.SubmitRequest request
    ) {
        return ApiResponse.ok(feedbackService.submit(reportId, request));
    }
}
