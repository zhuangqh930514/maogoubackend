package com.maogou.stock.infrastructure.market;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiSourceHealth;
import com.maogou.stock.mapper.research.AiSourceHealthMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentMarketSourceHealthRegistryTest {

    @Test
    void startsCooldownOnlyAfterConfiguredConsecutiveFailures() {
        AiSourceHealthMapper mapper = mock(AiSourceHealthMapper.class);
        AppProperties properties = new AppProperties();
        properties.getMarket().setSourceCooldownFailureThreshold(3);
        properties.getMarket().setSourceCooldownBaseSeconds(30);
        PersistentMarketSourceHealthRegistry registry =
                new PersistentMarketSourceHealthRegistry(mapper, properties);
        LocalDateTime attemptedAt = LocalDateTime.of(2026, 7, 15, 14, 0);

        AiSourceHealth existing = new AiSourceHealth();
        existing.consecutiveFailureCount = 1;
        when(mapper.selectHealth("SINA", "KLINE")).thenReturn(existing);
        when(mapper.upsert(any())).thenAnswer(invocation -> {
            AiSourceHealth saved = invocation.getArgument(0);
            assertThat(saved.consecutiveFailureCount).isEqualTo(2);
            assertThat(saved.cooldownUntil).isNull();
            return 1;
        });

        registry.recordFailure("sina", "kline", "temporary", attemptedAt);

        verify(mapper).upsert(any());
    }

    @Test
    void appliesExponentialCooldownFromTheThresholdFailure() {
        AiSourceHealthMapper mapper = mock(AiSourceHealthMapper.class);
        AppProperties properties = new AppProperties();
        properties.getMarket().setSourceCooldownFailureThreshold(3);
        properties.getMarket().setSourceCooldownBaseSeconds(30);
        PersistentMarketSourceHealthRegistry registry =
                new PersistentMarketSourceHealthRegistry(mapper, properties);
        LocalDateTime attemptedAt = LocalDateTime.of(2026, 7, 15, 14, 0);

        AiSourceHealth existing = new AiSourceHealth();
        existing.consecutiveFailureCount = 2;
        when(mapper.selectHealth("SINA", "KLINE")).thenReturn(existing);
        when(mapper.upsert(any())).thenAnswer(invocation -> {
            AiSourceHealth saved = invocation.getArgument(0);
            assertThat(saved.consecutiveFailureCount).isEqualTo(3);
            assertThat(saved.cooldownUntil).isEqualTo(attemptedAt.plusSeconds(30));
            return 1;
        });

        registry.recordFailure("SINA", "KLINE", "temporary", attemptedAt);

        verify(mapper).upsert(any());
    }
}
