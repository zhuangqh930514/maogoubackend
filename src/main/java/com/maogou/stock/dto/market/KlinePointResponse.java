package com.maogou.stock.dto.market;

import java.math.BigDecimal;
import java.time.LocalDate;

public record KlinePointResponse(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal close,
        BigDecimal low,
        BigDecimal high,
        Long volume,
        BigDecimal amount
) {
}
