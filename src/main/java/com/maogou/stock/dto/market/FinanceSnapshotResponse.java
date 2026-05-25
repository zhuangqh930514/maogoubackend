package com.maogou.stock.dto.market;

import java.math.BigDecimal;

public record FinanceSnapshotResponse(
        BigDecimal pe,
        BigDecimal pb,
        BigDecimal totalMarketValue,
        BigDecimal circulatingMarketValue,
        BigDecimal eps,
        BigDecimal bps,
        BigDecimal revenue,
        BigDecimal revenueGrowth,
        BigDecimal netProfit,
        BigDecimal profitGrowth,
        BigDecimal roe,
        BigDecimal grossMargin,
        BigDecimal netMargin,
        BigDecimal debtRatio,
        BigDecimal operatingCashFlowPerShare
) {
    public static FinanceSnapshotResponse empty() {
        return new FinanceSnapshotResponse(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }
}
