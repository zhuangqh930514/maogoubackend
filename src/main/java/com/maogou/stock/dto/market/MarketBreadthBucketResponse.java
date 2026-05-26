package com.maogou.stock.dto.market;

public record MarketBreadthBucketResponse(
        String label,
        int count,
        String direction
) {
}
