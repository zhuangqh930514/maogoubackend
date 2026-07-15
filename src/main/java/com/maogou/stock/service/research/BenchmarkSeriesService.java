package com.maogou.stock.service.research;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.infrastructure.market.ResearchMarketDataClient;
import com.maogou.stock.infrastructure.market.ResearchSourceResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

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
                normalizeBenchmarkSymbol(properties.getMarket().getBenchmarkSymbol()), "day", limit, asOfTime);
    }

    static String normalizeBenchmarkSymbol(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "000300.SH";
        }
        if (normalized.endsWith(".SH") || normalized.endsWith(".SZ")) {
            return normalized;
        }
        if (normalized.matches("399\\d{3}")) {
            return normalized + ".SZ";
        }
        if (normalized.matches("\\d{6}")) {
            return normalized + ".SH";
        }
        return normalized;
    }
}
