package com.maogou.stock.dto.market;

import com.maogou.stock.domain.enums.MarketDataMode;
import com.maogou.stock.domain.enums.MarketSessionStatus;
import com.maogou.stock.domain.enums.MarketSourceStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Common source contract for market data.  Existing list endpoints may still
 * return their historical payload shape, but every new source-aware payload
 * uses these same semantics.
 */
public record MarketDataEnvelope<T>(
        T data,
        MarketSourceStatus sourceStatus,
        MarketDataMode dataMode,
        MarketSessionStatus marketSession,
        LocalDate tradeDate,
        String sourceProvider,
        LocalDateTime sourceAsOf,
        LocalDateTime servedAt,
        long cacheAgeSeconds,
        String message,
        List<SourceAttempt> attempts
) {
    public MarketDataEnvelope {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        servedAt = servedAt == null ? LocalDateTime.now() : servedAt;
        message = message == null ? "" : message;
    }

    public record SourceAttempt(
            String provider,
            String endpointType,
            String status,
            LocalDateTime attemptedAt,
            String reason
    ) {
    }
}
