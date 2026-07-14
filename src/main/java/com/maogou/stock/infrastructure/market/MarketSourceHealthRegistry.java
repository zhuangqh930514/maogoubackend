package com.maogou.stock.infrastructure.market;

import java.time.LocalDateTime;

public interface MarketSourceHealthRegistry {

    boolean isCoolingDown(String providerCode, String endpointType, LocalDateTime now);

    void recordSuccess(
            String providerCode,
            String endpointType,
            String responseFingerprint,
            LocalDateTime attemptedAt
    );

    void recordFailure(
            String providerCode,
            String endpointType,
            String errorMessage,
            LocalDateTime attemptedAt
    );
}
