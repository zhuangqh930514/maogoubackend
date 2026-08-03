package com.maogou.stock.dto.chat;

import com.maogou.stock.domain.entity.AiChatMessage;
import com.maogou.stock.infrastructure.ai.AiModelErrorFormatter;

import java.time.LocalDateTime;

public record ChatMessageResponse(
        Long id,
        Long sessionId,
        String role,
        String content,
        String modelName,
        String status,
        String errorMessage,
        Long retryAfterSeconds,
        LocalDateTime createdAt
) {
    public static ChatMessageResponse from(AiChatMessage entity) {
        String safeContent = entity.content;
        String safeError = entity.errorMessage;
        if ("FAILED".equalsIgnoreCase(entity.status)) {
            safeContent = AiModelErrorFormatter.storedFailureMessage(
                    entity.content, entity.errorMessage, entity.modelName);
            safeError = safeContent;
        }
        return new ChatMessageResponse(
                entity.id,
                entity.sessionId,
                entity.messageRole,
                safeContent,
                entity.modelName,
                entity.status,
                safeError,
                entity.retryAfterSeconds,
                entity.createdAt
        );
    }
}
