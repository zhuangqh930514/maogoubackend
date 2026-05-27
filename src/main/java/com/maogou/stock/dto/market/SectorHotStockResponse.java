package com.maogou.stock.dto.market;

import java.math.BigDecimal;

public record SectorHotStockResponse(
        String code,
        String name,
        BigDecimal price,
        BigDecimal percent,
        BigDecimal netInflow,
        Long volume,
        BigDecimal amount,
        int rank
) {
}
