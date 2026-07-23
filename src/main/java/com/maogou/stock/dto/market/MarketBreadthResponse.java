package com.maogou.stock.dto.market;

import java.time.LocalDateTime;
import java.util.List;

public record MarketBreadthResponse(
        List<MarketBreadthBucketResponse> buckets,
        int upCount,
        int downCount,
        int flatCount,
        int limitUpCount,
        int limitDownCount,
        int fundInCount,
        int fundOutCount,
        int fundFlatCount,
        LocalDateTime updatedAt,
        String sourceStatus,
        String source,
        LocalDateTime sourceUpdatedAt,
        LocalDateTime servedAt,
        String message
) {
    public MarketBreadthResponse(
            List<MarketBreadthBucketResponse> buckets,
            int upCount,
            int downCount,
            int flatCount,
            int limitUpCount,
            int limitDownCount,
            int fundInCount,
            int fundOutCount,
            int fundFlatCount,
            LocalDateTime updatedAt
    ) {
        this(buckets, upCount, downCount, flatCount, limitUpCount, limitDownCount,
                fundInCount, fundOutCount, fundFlatCount, updatedAt, "REALTIME", "EASTMONEY", updatedAt,
                LocalDateTime.now(), "");
    }

    public static MarketBreadthResponse realtime(
            MarketBreadthResponse response,
            LocalDateTime sourceUpdatedAt
    ) {
        LocalDateTime updated = sourceUpdatedAt == null ? LocalDateTime.now() : sourceUpdatedAt;
        return new MarketBreadthResponse(
                response.buckets(), response.upCount(), response.downCount(), response.flatCount(),
                response.limitUpCount(), response.limitDownCount(), response.fundInCount(),
                response.fundOutCount(), response.fundFlatCount(), updated,
                "REALTIME", response.source() == null || response.source().isBlank() ? "EASTMONEY" : response.source(),
                updated, LocalDateTime.now(), "东方财富实时涨跌分布数据");
    }

    public static MarketBreadthResponse stale(
            MarketBreadthResponse response,
            LocalDateTime sourceUpdatedAt,
            String message
    ) {
        LocalDateTime updated = sourceUpdatedAt == null ? response.updatedAt() : sourceUpdatedAt;
        return new MarketBreadthResponse(
                response.buckets(), response.upCount(), response.downCount(), response.flatCount(),
                response.limitUpCount(), response.limitDownCount(), response.fundInCount(),
                response.fundOutCount(), response.fundFlatCount(), updated,
                "STALE", response.source() == null || response.source().isBlank() ? "EASTMONEY" : response.source(),
                updated, LocalDateTime.now(), message);
    }

    public static MarketBreadthResponse unavailable(String message) {
        LocalDateTime now = LocalDateTime.now();
        return new MarketBreadthResponse(
                List.of(), 0, 0, 0, 0, 0, 0, 0, 0, now,
                "UNAVAILABLE", "EASTMONEY", null, now, message);
    }
}
