package com.maogou.stock.service.impl;

import com.maogou.stock.dto.market.SectorHotStocksResponse;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.WatchlistService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HomeOverviewServiceImplTest {

    @Test
    void isolatesSectionFailuresAndStillReturnsTheOtherInitialData() {
        MarketDataService market = mock(MarketDataService.class);
        WatchlistService watchlist = mock(WatchlistService.class);
        AiAnalysisService ai = mock(AiAnalysisService.class);
        when(market.latestNews(50)).thenReturn(List.of());
        when(market.coreIndexes()).thenThrow(new IllegalStateException("指数源超时"));
        when(market.marketBreadth()).thenReturn(null);
        when(market.marketHotStocks(10)).thenReturn(SectorHotStocksResponse.unavailable("暂不可用"));
        when(watchlist.codes(null)).thenReturn(List.of("600519"));
        HomeOverviewServiceImpl service = new HomeOverviewServiceImpl(market, watchlist, ai);

        try {
            var response = service.overview();

            assertThat(response.watchlistCodes()).containsExactly("600519");
            assertThat(response.indexes()).isEmpty();
            assertThat(response.warnings()).containsKey("indexes");
            assertThat(response.hotStocks().sourceStatus()).isEqualTo("UNAVAILABLE");
        } finally {
            service.shutdown();
        }
    }
}
