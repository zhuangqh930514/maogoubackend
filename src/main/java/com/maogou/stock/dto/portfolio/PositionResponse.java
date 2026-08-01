package com.maogou.stock.dto.portfolio;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PositionResponse(
        String code,
        String name,
        BigDecimal buyPrice,
        Integer quantity,
        BigDecimal currentPrice,
        BigDecimal cost,
        BigDecimal marketValue,
        BigDecimal profit,
        BigDecimal profitRate,
        BigDecimal todayProfit,
        BigDecimal todayProfitRate,
        String quoteStatus,
        String quoteSource,
        LocalDateTime quoteAsOf,
        String calculationStatus,
        String unavailableReason
) {
    public PositionResponse(
            String code,
            String name,
            BigDecimal buyPrice,
            Integer quantity,
            BigDecimal currentPrice,
            BigDecimal cost,
            BigDecimal marketValue,
            BigDecimal profit,
            BigDecimal profitRate,
            BigDecimal todayProfit,
            BigDecimal todayProfitRate
    ) {
        this(code, name, buyPrice, quantity, currentPrice, cost, marketValue, profit, profitRate,
                todayProfit, todayProfitRate, "UNAVAILABLE", null, null, "UNAVAILABLE", "行情状态未提供");
    }
}
