package com.maogou.stock.infrastructure.market;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HistoricalProviderRetryExecutorImplTest {

    @Test
    void retriesTransientFailuresWithDocumentedBackoff() {
        MarketSourceHealthRegistry health = mock(MarketSourceHealthRegistry.class);
        List<Duration> sleeps = new ArrayList<>();
        HistoricalProviderRetryExecutorImpl executor = new HistoricalProviderRetryExecutorImpl(
                health, fixedClock(), sleeps::add, value -> value, 5, 60_000);
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute("TUSHARE", "DAILY", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("Read timed out");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5));
        verify(health).recordSuccess(anyString(), anyString(), anyString(), any());
    }

    @Test
    void doesNotRetryPermissionOrSchemaFailures() {
        MarketSourceHealthRegistry health = mock(MarketSourceHealthRegistry.class);
        List<Duration> sleeps = new ArrayList<>();
        HistoricalProviderRetryExecutorImpl executor = new HistoricalProviderRetryExecutorImpl(
                health, fixedClock(), sleeps::add, value -> value, 5, 60_000);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("TUSHARE", "QFQ", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("403 permission denied");
        }))
                .isInstanceOf(HistoricalProviderRetryExecutor.ProviderRetryException.class)
                .hasMessageContaining("attempt=1/5")
                .hasMessageContaining("retryable=false");

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
        verify(health, never()).recordSuccess(anyString(), anyString(), anyString(), any());
    }

    @Test
    void honorsRetryAfterHeader() {
        MarketSourceHealthRegistry health = mock(MarketSourceHealthRegistry.class);
        List<Duration> sleeps = new ArrayList<>();
        HistoricalProviderRetryExecutorImpl executor = new HistoricalProviderRetryExecutorImpl(
                health, fixedClock(), sleeps::add, value -> value, 2, 60_000);

        assertThatThrownBy(() -> executor.execute("TUSHARE", "DAILY", () -> {
            throw new IllegalStateException("HTTP 429 retry-after: 17");
        }))
                .isInstanceOf(HistoricalProviderRetryExecutor.ProviderRetryException.class)
                .hasMessageContaining("attempt=2/2");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(17));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}
