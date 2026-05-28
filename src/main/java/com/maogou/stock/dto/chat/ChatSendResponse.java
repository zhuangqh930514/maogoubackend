package com.maogou.stock.dto.chat;

import java.util.List;

public record ChatSendResponse(
        ChatSessionResponse session,
        ChatMessageResponse userMessage,
        ChatMessageResponse assistantMessage,
        ChatMemoryResponse memory,
        Boolean webSearchEnabled,
        List<ChatWebSearchResultResponse> webSearchResults,
        String webSearchError
) {
}
