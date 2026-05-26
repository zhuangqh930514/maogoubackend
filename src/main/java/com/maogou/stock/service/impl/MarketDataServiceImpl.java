package com.maogou.stock.service.impl;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.*;
import com.maogou.stock.infrastructure.market.MarketDataClient;
import com.maogou.stock.service.MarketDataService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Service
public class MarketDataServiceImpl implements MarketDataService {

    private final MarketDataClient marketDataClient;
    private final AppProperties properties;
    private final ConcurrentMap<String, CacheEntry<StockQuoteResponse>> quoteCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<FinanceSnapshotResponse>> financeCache = new ConcurrentHashMap<>();

    public MarketDataServiceImpl(MarketDataClient marketDataClient, AppProperties properties) {
        this.marketDataClient = marketDataClient;
        this.properties = properties;
    }

    @Override
    public List<StockSearchResponse> searchStocks(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return marketDataClient.searchStocks(keyword.trim(), limit);
    }

    @Override
    public List<NewsFlashResponse> latestNews(int limit) {
        return marketDataClient.fetchLatestNews(limit);
    }

    @Override
    public List<MarketIndexResponse> coreIndexes() {
        return marketDataClient.fetchCoreIndexes();
    }

    @Override
    public MarketBreadthResponse marketBreadth() {
        return marketDataClient.fetchMarketBreadth();
    }

    @Override
    public List<IntradayPointResponse> intraday(String symbol) {
        return marketDataClient.fetchIntraday(symbol);
    }

    @Override
    public List<KlinePointResponse> kline(String symbol, String period, int limit) {
        return marketDataClient.fetchKline(symbol, period, limit);
    }

    @Override
    public StockDetailResponse stockDetail(String code) {
        StockQuoteResponse quote = quote(code);
        FinanceSnapshotResponse finance = finance(code);
        List<IntradayPointResponse> intraday = intraday(code);
        List<KlinePointResponse> kline = kline(code, "day", 60);
        return new StockDetailResponse(
                quote,
                finance,
                intraday,
                kline,
                adviceByPercent(quote.percent()),
                scoreByPercent(quote.percent())
        );
    }

    @Override
    public StockQuoteResponse quote(String code) {
        String normalizedCode = normalizeCode(code);
        return cached(
                quoteCache,
                normalizedCode,
                Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds()),
                () -> marketDataClient.fetchQuote(normalizedCode)
        );
    }

    @Override
    public FinanceSnapshotResponse finance(String code) {
        String normalizedCode = normalizeCode(code);
        return cached(
                financeCache,
                normalizedCode,
                Duration.ofSeconds(properties.getMarket().getFinanceCacheTtlSeconds()),
                () -> marketDataClient.fetchFinance(normalizedCode)
        );
    }

    private static <T> T cached(
            ConcurrentMap<String, CacheEntry<T>> cache,
            String key,
            Duration ttl,
            Supplier<T> loader
    ) {
        long ttlMillis = ttl.toMillis();
        if (ttlMillis <= 0) {
            return loader.get();
        }
        long now = System.currentTimeMillis();
        CacheEntry<T> existing = cache.get(key);
        if (existing != null && existing.expiresAtMillis > now) {
            return existing.value;
        }
        return cache.compute(key, (cacheKey, current) -> {
            long computeNow = System.currentTimeMillis();
            if (current != null && current.expiresAtMillis > computeNow) {
                return current;
            }
            return new CacheEntry<>(loader.get(), computeNow + ttlMillis);
        }).value;
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim();
    }

    private static String adviceByPercent(BigDecimal percent) {
        if (percent.compareTo(new BigDecimal("3")) >= 0) {
            return "突破跟踪";
        }
        if (percent.compareTo(BigDecimal.ZERO) < 0) {
            return "控制仓位";
        }
        return "稳健持有";
    }

    private static int scoreByPercent(BigDecimal percent) {
        if (percent.compareTo(new BigDecimal("3")) >= 0) {
            return 86;
        }
        if (percent.compareTo(BigDecimal.ZERO) < 0) {
            return 64;
        }
        return 72;
    }

    private record CacheEntry<T>(T value, long expiresAtMillis) {
    }
}
