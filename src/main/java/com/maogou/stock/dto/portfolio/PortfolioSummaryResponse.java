package com.maogou.stock.dto.portfolio;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummaryResponse(
        BigDecimal totalCost,
        BigDecimal totalMarketValue,
        BigDecimal totalProfit,
        BigDecimal profitRate,
        BigDecimal todayProfit,
        BigDecimal todayProfitRate,
        List<PositionResponse> positions,
        long positionTotal,
        int page,
        int pageSize,
        int totalPages,
        long pricedPositionCount,
        long unpricedPositionCount,
        boolean summaryComplete
) {
    public PortfolioSummaryResponse(
            BigDecimal totalCost,
            BigDecimal totalMarketValue,
            BigDecimal totalProfit,
            BigDecimal profitRate,
            BigDecimal todayProfit,
            BigDecimal todayProfitRate,
            List<PositionResponse> positions,
            long positionTotal,
            int page,
            int pageSize,
            int totalPages
    ) {
        this(totalCost, totalMarketValue, totalProfit, profitRate, todayProfit, todayProfitRate,
                positions, positionTotal, page, pageSize, totalPages, 0, 0, true);
    }
}
