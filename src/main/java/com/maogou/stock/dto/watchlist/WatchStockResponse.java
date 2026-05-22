package com.maogou.stock.dto.watchlist;

import java.math.BigDecimal;

public record WatchStockResponse(
        Long id,
        String code,
        String name,
        BigDecimal price,
        BigDecimal percent,
        BigDecimal volumeRatio,
        Integer aiScore,
        String advice,
        BigDecimal pe,
        BigDecimal pb,
        BigDecimal revenueGrowth,
        BigDecimal profitGrowth,
        String groupName
) {
}
