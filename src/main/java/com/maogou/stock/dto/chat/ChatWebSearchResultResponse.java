package com.maogou.stock.dto.chat;

import com.maogou.stock.infrastructure.search.WebSearchItem;

public record ChatWebSearchResultResponse(
        String title,
        String url,
        String snippet
) {
    public static ChatWebSearchResultResponse from(WebSearchItem item) {
        return new ChatWebSearchResultResponse(item.title(), item.url(), item.snippet());
    }
}
