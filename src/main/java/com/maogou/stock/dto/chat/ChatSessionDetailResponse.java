package com.maogou.stock.dto.chat;

import java.util.List;

public record ChatSessionDetailResponse(
        ChatSessionResponse session,
        ChatMemoryResponse memory,
        List<ChatMessageResponse> messages
) {
}
