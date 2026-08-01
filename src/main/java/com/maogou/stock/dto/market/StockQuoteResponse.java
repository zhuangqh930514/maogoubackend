package com.maogou.stock.dto.market;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        LocalDateTime fetchedAt,
        String sourceStatus,
        String dataMode,
        String marketSession,
        LocalDate tradeDate,
        LocalDateTime sourceAsOf,
        LocalDateTime servedAt,
        String message
) {
    public StockQuoteResponse(
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
        this(code, name, price, change, percent, volumeRatio, market, source, fetchedAt,
                "REALTIME", "LIVE", "TRADING",
                fetchedAt == null ? null : fetchedAt.toLocalDate(), fetchedAt,
                LocalDateTime.now(), "");
    }

    public static StockQuoteResponse unavailable(String code, String name, String message) {
        LocalDateTime now = LocalDateTime.now();
        return new StockQuoteResponse(code, name == null ? code : name, null, null, null, null,
                null, "UNAVAILABLE", null, "UNAVAILABLE", "UNAVAILABLE", "CLOSED", null,
                null, now, message);
    }

    public static StockQuoteResponse stale(StockQuoteResponse value, String message) {
        if (value == null) {
            return unavailable(null, null, message);
        }
        return new StockQuoteResponse(value.code(), value.name(), value.price(), value.change(), value.percent(),
                value.volumeRatio(), value.market(), value.source(), value.fetchedAt(), "STALE",
                "RECENT_CLOSE", value.marketSession(), value.tradeDate(), value.sourceAsOf(),
                LocalDateTime.now(), message);
    }

    public StockQuoteResponse withSourceMetadata(
            String normalizedStatus,
            String normalizedMode,
            String normalizedSession,
            LocalDate normalizedTradeDate,
            LocalDateTime normalizedSourceAsOf,
            String normalizedMessage
    ) {
        return new StockQuoteResponse(code, name, price, change, percent, volumeRatio, market, source, fetchedAt,
                normalizedStatus, normalizedMode, normalizedSession, normalizedTradeDate, normalizedSourceAsOf,
                LocalDateTime.now(), normalizedMessage);
    }

    public boolean hasUsablePrice() {
        return price != null && price.signum() > 0 && !"UNAVAILABLE".equalsIgnoreCase(sourceStatus)
                && !"MOCK".equalsIgnoreCase(source) && !"LOCAL_FALLBACK".equalsIgnoreCase(source);
    }
}
