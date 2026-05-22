package com.maogou.stock.dto.market;

import java.math.BigDecimal;

public record StockQuoteResponse(
        String code,
        String name,
        BigDecimal price,
        BigDecimal change,
        BigDecimal percent,
        BigDecimal volumeRatio,
        String market
) {
}
