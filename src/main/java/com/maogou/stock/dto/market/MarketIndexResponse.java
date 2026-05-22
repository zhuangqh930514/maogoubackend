package com.maogou.stock.dto.market;

import java.math.BigDecimal;
import java.util.List;

public record MarketIndexResponse(
        String name,
        String code,
        BigDecimal value,
        BigDecimal change,
        BigDecimal percent,
        List<BigDecimal> trend
) {
}
