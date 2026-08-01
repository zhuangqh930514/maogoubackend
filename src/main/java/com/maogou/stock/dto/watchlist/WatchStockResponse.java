package com.maogou.stock.dto.watchlist;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        String groupName,
        boolean pinned,
        String quoteStatus,
        String quoteSource,
        LocalDateTime quoteAsOf,
        String calculationStatus,
        String unavailableReason
) {
    public WatchStockResponse(
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
            String groupName,
            boolean pinned
    ) {
        this(id, code, name, price, percent, volumeRatio, aiScore, advice, pe, pb, revenueGrowth,
                profitGrowth, groupName, pinned, "UNAVAILABLE", null, null, "UNAVAILABLE", "行情状态未提供");
    }
}
