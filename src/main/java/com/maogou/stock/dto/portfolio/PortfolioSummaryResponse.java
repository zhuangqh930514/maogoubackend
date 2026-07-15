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
        int totalPages
) {
}
