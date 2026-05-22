package com.maogou.stock.dto.portfolio;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummaryResponse(
        BigDecimal totalCost,
        BigDecimal totalMarketValue,
        BigDecimal totalProfit,
        BigDecimal profitRate,
        List<PositionResponse> positions
) {
}
