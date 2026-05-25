package com.maogou.stock.dto.chat;

import com.maogou.stock.domain.entity.AiUserMemory;

import java.time.LocalDateTime;

public record ChatMemoryResponse(
        String memorySummary,
        LocalDateTime lastInteractionAt,
        LocalDateTime updatedAt
) {
    public static ChatMemoryResponse from(AiUserMemory entity) {
        return new ChatMemoryResponse(
                entity.memorySummary,
                entity.lastInteractionAt,
                entity.updatedAt
        );
    }
}
