package com.maogou.stock.dto.chat;

import com.maogou.stock.domain.entity.AiChatSession;

import java.time.LocalDateTime;

public record ChatSessionResponse(
        Long id,
        String title,
        String modelName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ChatSessionResponse from(AiChatSession entity) {
        return new ChatSessionResponse(
                entity.id,
                entity.title,
                entity.modelName,
                entity.createdAt,
                entity.updatedAt
        );
    }
}
