package com.maogou.stock.dto.watchlist;

public record WatchlistQuery(
        String view,
        String keyword,
        String sort,
        boolean pinnedOnly,
        int page,
        int pageSize
) {
}
