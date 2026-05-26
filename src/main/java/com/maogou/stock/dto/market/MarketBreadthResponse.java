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
        LocalDateTime updatedAt
) {
}
