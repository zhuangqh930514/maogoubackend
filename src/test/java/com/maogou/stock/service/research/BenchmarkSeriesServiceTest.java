package com.maogou.stock.service.research;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.infrastructure.market.ResearchMarketDataClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BenchmarkSeriesServiceTest {

    @Test
    void normalizesBareCsiBenchmarkCodeToTheShanghaiIndex() {
        ResearchMarketDataClient marketDataClient = mock(ResearchMarketDataClient.class);
        AppProperties properties = new AppProperties();
        properties.getMarket().setBenchmarkSymbol("000300");
        BenchmarkSeriesService service = new BenchmarkSeriesService(marketDataClient, properties);
        LocalDateTime asOfTime = LocalDateTime.of(2026, 7, 15, 16, 0);

        service.load(asOfTime, 240);

        verify(marketDataClient).fetchKlineAt("000300.SH", "day", 240, asOfTime);
    }

    @Test
    void preservesExplicitExchangeAndNormalizesShenzhenIndexCodes() {
        assertThat(BenchmarkSeriesService.normalizeBenchmarkSymbol("000300.SH")).isEqualTo("000300.SH");
        assertThat(BenchmarkSeriesService.normalizeBenchmarkSymbol("399006")).isEqualTo("399006.SZ");
        assertThat(BenchmarkSeriesService.normalizeBenchmarkSymbol(null)).isEqualTo("000300.SH");
    }
}
