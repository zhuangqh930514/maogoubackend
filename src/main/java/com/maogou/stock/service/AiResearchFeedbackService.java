package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiResearchFeedbackPayload;

import java.util.List;

/**
 * Stores user-facing explanation feedback only. Implementations must never
 * feed these values into labels, factor performance, model training or governance.
 */
public interface AiResearchFeedbackService {

    List<AiResearchFeedbackPayload.Item> list(Long reportId);

    AiResearchFeedbackPayload.Item submit(Long reportId, AiResearchFeedbackPayload.SubmitRequest request);
}
