package com.maogou.stock.dto.chat;

import com.maogou.stock.domain.entity.AiChatMessage;

import java.time.LocalDateTime;

public record ChatMessageResponse(
        Long id,
        Long sessionId,
        String role,
        String content,
        String modelName,
        String status,
        String errorMessage,
        LocalDateTime createdAt
) {
    public static ChatMessageResponse from(AiChatMessage entity) {
        return new ChatMessageResponse(
                entity.id,
                entity.sessionId,
                entity.messageRole,
                entity.content,
                entity.modelName,
                entity.status,
                entity.errorMessage,
                entity.createdAt
        );
    }
}
