package com.maogou.stock.dto.ai;

import com.maogou.stock.domain.entity.AiResearchUserFeedback;

import java.time.LocalDateTime;

public final class AiResearchFeedbackPayload {

    private AiResearchFeedbackPayload() {
    }

    public record SubmitRequest(String stockCode, String feedbackType, String comment) {
    }

    public record Item(Long reportId, String stockCode, String feedbackType, String comment, LocalDateTime updatedAt) {
        public static Item from(AiResearchUserFeedback entity) {
            return new Item(entity.reportId, entity.stockCode, entity.feedbackType, entity.comment, entity.updatedAt);
        }
    }
}
