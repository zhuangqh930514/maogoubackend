package com.maogou.stock.dto.chat;

import jakarta.validation.constraints.Size;

public record UpdateChatMemoryRequest(
        @Size(max = 4000) String memorySummary
) {
}
