package com.maogou.stock.dto.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
        BigDecimal operatingCashFlowPerShare,
        LocalDate reportDate,
        LocalDateTime publishedAt,
        LocalDateTime fetchedAt,
        String source
) {
    public FinanceSnapshotResponse(
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
        this(pe, pb, totalMarketValue, circulatingMarketValue, eps, bps, revenue, revenueGrowth,
                netProfit, profitGrowth, roe, grossMargin, netMargin, debtRatio, operatingCashFlowPerShare,
                null, null, null, null);
    }

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
                BigDecimal.ZERO,
                null,
                null,
                null,
                null
        );
    }
}
