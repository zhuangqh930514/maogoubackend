package com.maogou.stock.dto.market;

import java.time.LocalDateTime;
import java.util.List;

public record SectorHotStocksResponse(
        List<SectorHotStockResponse> items,
        String sourceStatus,
        String source,
        LocalDateTime sourceUpdatedAt,
        LocalDateTime servedAt,
        String message
) {
    public static SectorHotStocksResponse realtime(List<SectorHotStockResponse> items, LocalDateTime sourceUpdatedAt) {
        return new SectorHotStocksResponse(items, "REALTIME", "EASTMONEY", sourceUpdatedAt, LocalDateTime.now(), "东方财富实时热门股票数据");
    }

    public static SectorHotStocksResponse stale(List<SectorHotStockResponse> items, LocalDateTime sourceUpdatedAt, String message) {
        return new SectorHotStocksResponse(items, "STALE", "EASTMONEY", sourceUpdatedAt, LocalDateTime.now(), message);
    }

    public static SectorHotStocksResponse unavailable(String message) {
        return new SectorHotStocksResponse(List.of(), "UNAVAILABLE", "EASTMONEY", null, LocalDateTime.now(), message);
    }
}
