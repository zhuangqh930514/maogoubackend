package com.maogou.stock.dto.chat;

public record ChatSendResponse(
        ChatSessionResponse session,
        ChatMessageResponse userMessage,
        ChatMessageResponse assistantMessage,
        ChatMemoryResponse memory
) {
}
