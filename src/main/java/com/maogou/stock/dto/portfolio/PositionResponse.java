package com.maogou.stock.dto.portfolio;

import java.math.BigDecimal;

public record PositionResponse(
        String code,
        String name,
        BigDecimal buyPrice,
        Integer quantity,
        BigDecimal currentPrice,
        BigDecimal cost,
        BigDecimal marketValue,
        BigDecimal profit,
        BigDecimal profitRate
) {
}
