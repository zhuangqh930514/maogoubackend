package com.maogou.stock.infrastructure.market;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientMarketDataClientTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 14, 16, 0);

    @Test
    void fallsBackOnceAndSkipsTheFailedPrimaryDuringCooldown() {
        StubProvider primary = new StubProvider("EASTMONEY");
        primary.failure = new IllegalStateException("timeout");
        StubProvider backup = new StubProvider("SINA");
        backup.series = series("600519", "SINA", "10");
        AtomicBoolean cooling = new AtomicBoolean(false);
        MarketSourceHealthRegistry health = mock(MarketSourceHealthRegistry.class);
        when(health.isCoolingDown(anyString(), anyString(), any())).thenAnswer(invocation ->
                "EASTMONEY".equals(invocation.getArgument(0)) && cooling.get());
        doAnswer(invocation -> {
            if ("EASTMONEY".equals(invocation.getArgument(0))) {
                cooling.set(true);
            }
            return null;
        }).when(health).recordFailure(anyString(), anyString(), anyString(), any());
        ResilientMarketDataClient client = client(List.of(primary, backup), health);

        ResearchSourceResult<KlineSeriesSnapshot> first = client.fetchKlineAt("600519", "day", 60, AS_OF);
        backup.series = series("000001", "SINA", "11");
        ResearchSourceResult<KlineSeriesSnapshot> second = client.fetchKlineAt("000001", "day", 60, AS_OF);

        assertThat(first.sourceStatus()).isEqualTo(ResearchSourceStatus.REALTIME);
        assertThat(first.providerCode()).isEqualTo("SINA");
        assertThat(second.sourceStatus()).isEqualTo(ResearchSourceStatus.REALTIME);
        assertThat(primary.calls.get()).isEqualTo(1);
        assertThat(backup.calls.get()).isEqualTo(2);
        verify(health).recordFailure(anyString(), anyString(), anyString(), any());
    }

    @Test
    void returnsOnlyRealStaleCacheWhenEveryProviderFails() {
        StubProvider primary = new StubProvider("EASTMONEY");
        StubProvider backup = new StubProvider("SINA");
        primary.series = series("600519", "EASTMONEY", "10");
        backup.failure = new IllegalStateException("sina unavailable");
        ResilientMarketDataClient client = client(List.of(primary, backup), mock(MarketSourceHealthRegistry.class));
        ResearchSourceResult<KlineSeriesSnapshot> realtime = client.fetchKlineAt("600519", "day", 60, AS_OF);
        primary.failure = new IllegalStateException("eastmoney unavailable");
        primary.series = null;

        ResearchSourceResult<KlineSeriesSnapshot> stale = client.fetchKlineAt("600519", "day", 60, AS_OF);

        assertThat(realtime.sourceStatus()).isEqualTo(ResearchSourceStatus.REALTIME);
        assertThat(stale.sourceStatus()).isEqualTo(ResearchSourceStatus.STALE);
        assertThat(stale.formalReady()).isFalse();
        assertThat(stale.data().source()).doesNotContainIgnoringCase("mock");
    }

    @Test
    void rejectsConflictingProviderBarsInsteadOfChoosingOne() {
        StubProvider primary = new StubProvider("EASTMONEY");
        StubProvider backup = new StubProvider("SINA");
        primary.series = series("600519", "EASTMONEY", "10");
        backup.series = series("600519", "SINA", "20");
        ResilientMarketDataClient client = client(List.of(primary, backup), mock(MarketSourceHealthRegistry.class));

        ResearchSourceResult<KlineSeriesSnapshot> result = client.fetchKlineAt("600519", "day", 60, AS_OF);

        assertThat(result.sourceStatus()).isEqualTo(ResearchSourceStatus.UNAVAILABLE);
        assertThat(result.qualityStatus()).isEqualTo("SOURCE_CONFLICT");
        assertThat(result.formalReady()).isFalse();
        assertThat(result.message()).contains("冲突");
    }

    @Test
    void recordsProviderContextWithoutLosingTheRootCause() {
        StubProvider primary = new StubProvider("EASTMONEY");
        primary.failure = new IllegalStateException(
                "东方财富 K 线通道失败", new IllegalStateException("Unexpected end of file"));
        MarketSourceHealthRegistry health = mock(MarketSourceHealthRegistry.class);
        ResilientMarketDataClient client = client(List.of(primary), health);

        client.fetchKlineAt("600519", "day", 60, AS_OF);

        verify(health).recordFailure(
                "EASTMONEY", ResearchMarketDataProvider.ENDPOINT_KLINE,
                "东方财富 K 线通道失败；根因：Unexpected end of file", AS_OF);
    }

    private static ResilientMarketDataClient client(
            List<ResearchMarketDataProvider> providers,
            MarketSourceHealthRegistry health
    ) {
        AppProperties properties = new AppProperties();
        properties.getMarket().setResearchProviderOrder("EASTMONEY,SINA");
        properties.getMarket().setSourcePriceTolerancePct(new BigDecimal("0.005"));
        return new ResilientMarketDataClient(providers, health, properties, fixedClock());
    }

    private static KlineSeriesSnapshot series(String symbol, String source, String close) {
        BigDecimal price = new BigDecimal(close);
        List<KlinePointResponse> points = List.of(
                point("2026-07-13", price),
                point("2026-07-14", price));
        return KlineSeriesSnapshot.create(symbol, "day", "NONE", source, AS_OF, AS_OF, points);
    }

    private static KlinePointResponse point(String date, BigDecimal close) {
        return new KlinePointResponse(LocalDate.parse(date), close, close, close, close,
                100_000L, close.multiply(BigDecimal.valueOf(100_000)));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-14T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private static final class StubProvider implements ResearchMarketDataProvider {
        private final String providerCode;
        private final AtomicInteger calls = new AtomicInteger();
        private KlineSeriesSnapshot series;
        private RuntimeException failure;

        private StubProvider(String providerCode) {
            this.providerCode = providerCode;
        }

        @Override
        public String providerCode() {
            return providerCode;
        }

        @Override
        public boolean supports(String endpointType) {
            return ENDPOINT_KLINE.equals(endpointType);
        }

        @Override
        public KlineSeriesSnapshot fetchKlineAt(String symbol, String period, int limit, LocalDateTime asOfTime) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return series;
        }
    }
}
