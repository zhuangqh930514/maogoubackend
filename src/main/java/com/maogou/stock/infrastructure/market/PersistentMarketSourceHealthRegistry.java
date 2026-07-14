package com.maogou.stock.infrastructure.market;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiSourceHealth;
import com.maogou.stock.mapper.research.AiSourceHealthMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Component
public class PersistentMarketSourceHealthRegistry implements MarketSourceHealthRegistry {

    private final AiSourceHealthMapper mapper;
    private final AppProperties properties;

    public PersistentMarketSourceHealthRegistry(AiSourceHealthMapper mapper, AppProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public boolean isCoolingDown(String providerCode, String endpointType, LocalDateTime now) {
        AiSourceHealth health = mapper.selectHealth(normalize(providerCode), normalize(endpointType));
        return health != null && health.cooldownUntil != null && now.isBefore(health.cooldownUntil);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(
            String providerCode,
            String endpointType,
            String responseFingerprint,
            LocalDateTime attemptedAt
    ) {
        AiSourceHealth health = base(providerCode, endpointType, attemptedAt);
        health.sourceStatus = ResearchSourceStatus.REALTIME.name();
        health.lastSuccessAt = attemptedAt;
        health.consecutiveFailureCount = 0;
        health.cooldownUntil = null;
        health.lastErrorMessage = null;
        health.lastResponseFingerprint = responseFingerprint;
        mapper.upsert(health);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            String providerCode,
            String endpointType,
            String errorMessage,
            LocalDateTime attemptedAt
    ) {
        String provider = normalize(providerCode);
        String endpoint = normalize(endpointType);
        AiSourceHealth existing = mapper.selectHealth(provider, endpoint);
        int failures = existing == null || existing.consecutiveFailureCount == null
                ? 1 : Math.min(30, existing.consecutiveFailureCount + 1);
        long baseSeconds = Math.max(1, properties.getMarket().getSourceCooldownBaseSeconds());
        long maxSeconds = Math.max(baseSeconds, properties.getMarket().getSourceCooldownMaxSeconds());
        long multiplier = 1L << Math.min(20, failures - 1);
        long cooldownSeconds = Math.min(maxSeconds, baseSeconds * multiplier);

        AiSourceHealth health = base(provider, endpoint, attemptedAt);
        health.sourceStatus = ResearchSourceStatus.UNAVAILABLE.name();
        health.lastSuccessAt = existing == null ? null : existing.lastSuccessAt;
        health.consecutiveFailureCount = failures;
        health.cooldownUntil = attemptedAt.plusSeconds(cooldownSeconds);
        health.lastErrorMessage = truncate(errorMessage, 1024);
        health.lastResponseFingerprint = existing == null ? null : existing.lastResponseFingerprint;
        mapper.upsert(health);
    }

    private static AiSourceHealth base(String providerCode, String endpointType, LocalDateTime now) {
        AiSourceHealth health = new AiSourceHealth();
        health.providerCode = normalize(providerCode);
        health.endpointType = normalize(endpointType);
        health.lastAttemptAt = now;
        health.createdAt = now;
        health.updatedAt = now;
        return health;
    }

    private static String normalize(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
