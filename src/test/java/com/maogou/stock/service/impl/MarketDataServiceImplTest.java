package com.maogou.stock.service.impl;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.SectorHeatmapItemResponse;
import com.maogou.stock.dto.market.SectorHeatmapResponse;
import com.maogou.stock.dto.market.SectorHotStocksResponse;
import com.maogou.stock.infrastructure.market.MockMarketDataClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
}
