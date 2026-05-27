package com.maogou.stock.dto.settings;

import java.time.LocalDateTime;

public record PromptTemplateResponse(
        Long id,
        String title,
        String content,
        LocalDateTime updatedAt
) {
}
