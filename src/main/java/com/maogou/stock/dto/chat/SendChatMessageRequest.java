package com.maogou.stock.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendChatMessageRequest(
        @NotBlank @Size(max = 8000) String content
) {
}
