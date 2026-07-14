package com.maogou.stock.service.research;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.infrastructure.market.ResearchMarketDataClient;
import com.maogou.stock.infrastructure.market.ResearchSourceResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class BenchmarkSeriesService {

    private final ResearchMarketDataClient marketDataClient;
    private final AppProperties properties;

    public BenchmarkSeriesService(
            ResearchMarketDataClient marketDataClient,
            AppProperties properties
    ) {
        this.marketDataClient = marketDataClient;
        this.properties = properties;
    }

    public ResearchSourceResult<KlineSeriesSnapshot> load(LocalDateTime asOfTime, int limit) {
        return marketDataClient.fetchKlineAt(
                properties.getMarket().getBenchmarkSymbol(), "day", limit, asOfTime);
    }
}
