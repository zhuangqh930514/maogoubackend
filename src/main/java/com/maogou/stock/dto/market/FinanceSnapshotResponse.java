package com.maogou.stock.dto.market;

import java.math.BigDecimal;

public record FinanceSnapshotResponse(
        BigDecimal pe,
        BigDecimal pb,
        BigDecimal revenueGrowth,
        BigDecimal profitGrowth
) {
}
