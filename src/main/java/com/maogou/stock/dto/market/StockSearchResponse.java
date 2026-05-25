package com.maogou.stock.dto.market;

public record StockSearchResponse(
        String code,
        String name,
        String market,
        String symbol
) {
}
