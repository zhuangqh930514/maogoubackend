package com.maogou.stock.infrastructure.market;

import java.time.LocalDateTime;
import java.util.List;

public record ResearchSourceResult<T>(
        T data,
        ResearchSourceStatus sourceStatus,
        String qualityStatus,
        String providerCode,
        LocalDateTime sourceUpdatedAt,
        LocalDateTime fetchedAt,
        String responseFingerprint,
        String message,
        List<ProviderAttempt> attempts
) {
    public ResearchSourceResult {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public boolean formalReady() {
        return sourceStatus == ResearchSourceStatus.REALTIME
                && "READY".equals(qualityStatus)
                && data != null;
    }

    public record ProviderAttempt(
            String providerCode,
            String endpointType,
            ResearchSourceStatus status,
            LocalDateTime attemptedAt,
            String responseFingerprint,
            String errorMessage
    ) {
    }
}
