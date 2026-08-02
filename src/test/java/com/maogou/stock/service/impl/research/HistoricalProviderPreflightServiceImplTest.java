package com.maogou.stock.service.impl.research;

import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.infrastructure.market.MarketSourceHealthRegistry;
import com.maogou.stock.service.research.HistoricalProviderPreflightService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HistoricalProviderPreflightServiceImplTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 8, 1, 16, 0);

    @Test
    void requiresCatalogAndBothAdjustmentModes() {
        HistoricalProviderPreflightService service = new HistoricalProviderPreflightServiceImpl(
                List.of(new StubProvider("REAL_PROVIDER", false, false)),
                mock(MarketSourceHealthRegistry.class), fixedClock());

        HistoricalProviderPreflightService.PreflightResult result = service.check(AS_OF, "000300.SH");

        assertThat(result.ready()).isFalse();
        assertThat(result.capabilities()).singleElement().satisfies(value -> {
            assertThat(value).containsEntry("preflightStatus", "UNAVAILABLE");
            assertThat(value.get("capabilities").toString()).contains("DAILY_BAR_QFQ=UNAVAILABLE");
        });
        assertThat(result.blockingIssues()).anyMatch(value ->
                "HISTORICAL_PROVIDER_PREFLIGHT_FAILED".equals(value.get("reasonCode")));
    }

    @Test
    void rejectsSyntheticProviderBeforeCallingIt() {
        StubProvider provider = new StubProvider("LOCAL_TEST_FIXTURE", true, false);
        HistoricalProviderPreflightService service = new HistoricalProviderPreflightServiceImpl(
                List.of(provider), mock(MarketSourceHealthRegistry.class), fixedClock());

        HistoricalProviderPreflightService.PreflightResult result = service.check(AS_OF, "000300.SH");

        assertThat(result.ready()).isFalse();
        assertThat(result.capabilities()).singleElement().satisfies(value -> {
            assertThat(value).containsEntry("preflightStatus", "REJECTED_SOURCE");
            assertThat(value).containsEntry("sourceOfTruth", false);
        });
        assertThat(provider.calls).isZero();
    }

    @Test
    void exposesReadyProviderCapabilities() {
        HistoricalProviderPreflightService service = new HistoricalProviderPreflightServiceImpl(
                List.of(new StubProvider("REAL_PROVIDER", false, true)),
                mock(MarketSourceHealthRegistry.class), fixedClock());

        HistoricalProviderPreflightService.PreflightResult result = service.check(AS_OF, "000300.SH");

        assertThat(result.ready()).isTrue();
        assertThat(result.blockingIssues()).isEmpty();
        assertThat(result.capabilities()).singleElement().satisfies(value -> {
            assertThat(value).containsEntry("preflightStatus", "READY");
            assertThat(value.get("endpoints").toString()).contains("HISTORICAL_UNIVERSE");
            assertThat(value.get("attempts").toString()).contains("READY");
        });
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private static final class StubProvider implements HistoricalMarketDataProvider {
        private final String code;
        private final boolean synthetic;
        private final boolean qfqReady;
        private int calls;

        private StubProvider(String code, boolean synthetic, boolean qfqReady) {
            this.code = code;
            this.synthetic = synthetic;
            this.qfqReady = qfqReady;
        }

        @Override
        public String providerCode() {
            return code;
        }

        @Override
        public boolean syntheticSource() {
            return synthetic;
        }

        @Override
        public UniverseCatalog fetchCurrentListedUniverse(int limit, LocalDateTime requestedAt) {
            calls++;
            return new UniverseCatalog(code, requestedAt, "https://provider/catalog", "catalog-fingerprint",
                    List.of(new Security("000001", "平安银行", "SZ", LocalDate.of(2000, 1, 1))));
        }

        @Override
        public Set<String> historicalCapabilities() {
            return Set.of(ENDPOINT_HISTORICAL_UNIVERSE, ENDPOINT_DAILY_BAR_NONE, ENDPOINT_DAILY_BAR_QFQ);
        }

        @Override
        public HistoricalUniverse fetchHistoricalUniverse(
                int limit, LocalDate tradeDate, LocalDateTime asOfTime
        ) {
            calls++;
            return new HistoricalUniverse(code, tradeDate, asOfTime, asOfTime,
                    "https://provider/historical-universe", "REVISION-1",
                    "historical-universe-fingerprint",
                    List.of(new Security("000001", "平安银行", "SZ", LocalDate.of(2000, 1, 1))));
        }

        @Override
        public KlineSeriesSnapshot fetchHistoricalKline(
                String symbol, int limit, LocalDateTime asOfTime, String adjustmentMode
        ) {
            calls++;
            if ("QFQ".equals(adjustmentMode) && !qfqReady) {
                throw new IllegalStateException("QFQ 权限未开通");
            }
            List<KlinePointResponse> points = new ArrayList<>();
            for (int index = 24; index >= 0; index--) {
                LocalDate date = asOfTime.toLocalDate().minusDays(index);
                BigDecimal close = BigDecimal.valueOf(10 + index * 0.1d);
                points.add(new KlinePointResponse(date, close, close, close, close, 100L, BigDecimal.TEN));
            }
            points.sort(java.util.Comparator.comparing(KlinePointResponse::tradeDate));
            return KlineSeriesSnapshot.create(symbol, "day", adjustmentMode, code,
                    asOfTime, asOfTime, points);
        }
    }
}
