package com.maogou.stock.service.impl;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.SectorHeatmapItemResponse;
import com.maogou.stock.dto.market.SectorHeatmapResponse;
import com.maogou.stock.dto.market.SectorHotStocksResponse;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.infrastructure.market.MockMarketDataClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataServiceImplTest {

    @Test
    void sectorHeatmapReturnsUnavailableInsteadOfMockFallbackWhenRealtimeSourceFailsWithoutCache() {
        MarketDataServiceImpl service = new MarketDataServiceImpl(new FailingSectorMarketClient(), new AppProperties());

        SectorHeatmapResponse response = service.sectorHeatmap();

        assertThat(response.sourceStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.items()).isEmpty();
    }

    @Test
    void sectorHotStocksReturnsUnavailableInsteadOfMockFallbackWhenRealtimeSourceFailsWithoutCache() {
        MarketDataServiceImpl service = new MarketDataServiceImpl(new FailingSectorMarketClient(), new AppProperties());

        SectorHotStocksResponse response = service.sectorHotStocks("BK1298", 10);

        assertThat(response.sourceStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.items()).isEmpty();
    }

    @Test
    void marketBreadthReturnsUnavailableInsteadOfMockFallbackWhenRealtimeSourceFailsWithoutCache() {
        MarketDataServiceImpl service = new MarketDataServiceImpl(new FailingSectorMarketClient(), new AppProperties());

        var response = service.marketBreadth();

        assertThat(response.sourceStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.buckets()).isEmpty();
        assertThat(response.message()).contains("涨跌分布");
    }

    @Test
    void sectorHeatmapReturnsStaleRealCacheWhenRealtimeSourceFailsAfterSuccess() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getMarket().setSectorHeatmapCacheTtlSeconds(1);
        ToggleSectorMarketClient client = new ToggleSectorMarketClient();
        MarketDataServiceImpl service = new MarketDataServiceImpl(client, properties);

        SectorHeatmapResponse realtime = service.sectorHeatmap();
        Thread.sleep(1100);
        client.fail = true;

        SectorHeatmapResponse stale = service.sectorHeatmap();

        assertThat(realtime.sourceStatus()).isEqualTo("REALTIME");
        assertThat(stale.sourceStatus()).isEqualTo("STALE");
        assertThat(stale.items()).extracting(SectorHeatmapItemResponse::name).containsExactly("真实板块");
        assertThat(stale.items()).extracting(SectorHeatmapItemResponse::name).doesNotContain("钼");
    }

    @Test
    void pointInTimeDetailUsesTheRequestedClosingBarWithoutFetchingCurrentQuoteOrIntraday() {
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 10, 16, 0);
        MarketDataServiceImpl service = new MarketDataServiceImpl(new PointInTimeClient(asOf), new AppProperties());

        StockDetailResponse detail = service.stockDetailAt("600519", asOf);

        assertThat(detail.quote().price()).isEqualByComparingTo("12.00");
        assertThat(detail.quote().change()).isEqualByComparingTo("2.00");
        assertThat(detail.quote().percent()).isEqualByComparingTo("20.0000");
        assertThat(detail.quote().fetchedAt()).isEqualTo(asOf);
        assertThat(detail.quote().source()).isEqualTo("EASTMONEY");
        assertThat(detail.kline()).extracting(KlinePointResponse::tradeDate)
                .containsExactly(java.time.LocalDate.of(2026, 7, 9), java.time.LocalDate.of(2026, 7, 10));
        assertThat(detail.intraday()).isEmpty();
    }

    @Test
    void fastQuotesNeverWaitForASlowExternalSourceAndPopulateTheCacheInBackground() throws Exception {
        BlockingQuoteClient client = new BlockingQuoteClient();
        MarketDataServiceImpl service = new MarketDataServiceImpl(client, new AppProperties());

        long startedAt = System.nanoTime();
        Map<String, StockQuoteResponse> first = service.quotesFast(List.of("600519"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(first).isEmpty();
        assertThat(elapsedMillis).isLessThan(200);
        assertThat(client.started.await(1, TimeUnit.SECONDS)).isTrue();
        client.release.countDown();
        assertThat(client.completed.await(1, TimeUnit.SECONDS)).isTrue();
        Map<String, StockQuoteResponse> cached = Map.of();
        for (int attempt = 0; attempt < 20 && !cached.containsKey("600519"); attempt++) {
            Thread.sleep(10);
            cached = service.quotesFast(List.of("600519"));
        }
        assertThat(cached).containsKey("600519");
    }

    private static class FailingSectorMarketClient extends MockMarketDataClient {
        @Override
        public SectorHeatmapResponse fetchSectorHeatmap() {
            throw new IllegalStateException("source down");
        }

        @Override
        public List<com.maogou.stock.dto.market.SectorHotStockResponse> fetchMarketHotStocks(int limit) {
            throw new IllegalStateException("source down");
        }

        @Override
        public com.maogou.stock.dto.market.MarketBreadthResponse fetchMarketBreadth() {
            throw new IllegalStateException("source down");
        }

        @Override
        public List<com.maogou.stock.dto.market.SectorHotStockResponse> fetchSectorHotStocks(String sectorCode, int limit) {
            throw new IllegalStateException("source down");
        }
    }

    private static class ToggleSectorMarketClient extends FailingSectorMarketClient {
        private boolean fail;

        @Override
        public SectorHeatmapResponse fetchSectorHeatmap() {
            if (fail) {
                throw new IllegalStateException("source down");
            }
            return new SectorHeatmapResponse(
                    List.of(new SectorHeatmapItemResponse(
                            "BKREAL",
                            "真实板块",
                            new BigDecimal("100.00"),
                            new BigDecimal("1.23"),
                            new BigDecimal("120000000"),
                            "up",
                            1
                    )),
                    LocalDateTime.parse("2026-06-11T10:00:00")
            );
        }
    }

    private static class PointInTimeClient extends MockMarketDataClient {
        private final LocalDateTime asOf;

        private PointInTimeClient(LocalDateTime asOf) {
            this.asOf = asOf;
        }

        @Override
        public com.maogou.stock.dto.market.StockQuoteResponse fetchQuote(String stockCode) {
            throw new AssertionError("时点化详情不得读取当前 quote");
        }

        @Override
        public List<com.maogou.stock.dto.market.IntradayPointResponse> fetchIntraday(String symbol) {
            throw new AssertionError("历史时点详情不得读取当前分时");
        }

        @Override
        public KlineSeriesSnapshot fetchKlineAt(String symbol, String period, int limit, LocalDateTime requestedAsOf) {
            return KlineSeriesSnapshot.create(
                    symbol, "day", "NONE", "EASTMONEY", requestedAsOf, LocalDateTime.now(),
                    List.of(
                            new KlinePointResponse(java.time.LocalDate.of(2026, 7, 9),
                                    BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L, BigDecimal.TEN),
                            new KlinePointResponse(java.time.LocalDate.of(2026, 7, 10),
                                    BigDecimal.TEN, new BigDecimal("12"), BigDecimal.TEN,
                                    new BigDecimal("12"), 120L, new BigDecimal("12"))));
        }

        @Override
        public FinanceSnapshotResponse fetchFinanceAt(String stockCode, LocalDateTime requestedAsOf) {
            return FinanceSnapshotResponse.empty();
        }
    }

    private static class BlockingQuoteClient extends MockMarketDataClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public Map<String, StockQuoteResponse> fetchQuotes(List<String> stockCodes) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                return super.fetchQuotes(stockCodes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            } finally {
                completed.countDown();
            }
        }
    }
}
