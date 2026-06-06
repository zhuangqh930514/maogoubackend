package com.maogou.stock.dto.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockQuoteResponse(
        String code,
        String name,
        BigDecimal price,
        BigDecimal change,
        BigDecimal percent,
        BigDecimal volumeRatio,
        String market,
        String source,
        LocalDateTime fetchedAt
) {
}
