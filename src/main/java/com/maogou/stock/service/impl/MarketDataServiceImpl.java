package com.maogou.stock.service.impl;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.*;
import com.maogou.stock.infrastructure.market.MarketDataClient;
import com.maogou.stock.infrastructure.market.MockMarketDataClient;
import com.maogou.stock.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Service
public class MarketDataServiceImpl implements MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataServiceImpl.class);
    private static final long QUOTE_SOURCE_FAILURE_COOLDOWN_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final long SECTOR_SOURCE_FAILURE_COOLDOWN_MILLIS = Duration.ofMinutes(3).toMillis();

    private final MarketDataClient marketDataClient;
    private final MarketDataClient fallbackMarketDataClient = new MockMarketDataClient();
    private final AppProperties properties;
    private final ConcurrentMap<String, CacheEntry<StockQuoteResponse>> quoteCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<FinanceSnapshotResponse>> financeCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<NewsFlashResponse>>> newsCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<MarketIndexResponse>>> indexCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<MarketBreadthResponse>> breadthCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<SectorHeatmapResponse>> sectorHeatmapCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<SectorHotStockResponse>>> hotStocksCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<IntradayPointResponse>>> intradayCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<KlinePointResponse>>> klineCache = new ConcurrentHashMap<>();
    private volatile long batchQuoteSourceUnavailableUntilMillis = 0;
    private volatile long sectorSourceUnavailableUntilMillis = 0;

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
        int size = Math.max(1, Math.min(limit, 50));
        return cachedWithFallback(
                newsCache,
                "latest:" + size,
                Duration.ofMinutes(5),
                () -> marketDataClient.fetchLatestNews(size),
                null
        );
    }

    @Override
    public List<MarketIndexResponse> coreIndexes() {
        return cachedWithFallback(
                indexCache,
                "core-indexes",
                Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds()),
                marketDataClient::fetchCoreIndexes,
                null
        );
    }

    @Override
    public MarketBreadthResponse marketBreadth() {
        return cachedWithFallback(
                breadthCache,
                "market-breadth",
                Duration.ofSeconds(30),
                marketDataClient::fetchMarketBreadth,
                fallbackMarketDataClient::fetchMarketBreadth
        );
    }

    @Override
    public SectorHeatmapResponse sectorHeatmap() {
        String key = "sector-heatmap";
        Duration ttl = Duration.ofSeconds(properties.getMarket().getSectorHeatmapCacheTtlSeconds());
        CacheEntry<SectorHeatmapResponse> existing = sectorHeatmapCache.get(key);
        if (isFresh(existing)) {
            return realtimeHeatmap(existing.value, existing.loadedAt);
        }
        if (isSectorSourceCoolingDown()) {
            return staleOrUnavailableHeatmap(existing, "东方财富板块热力图数据源短暂不可用，稍后自动重试。");
        }
        try {
            SectorHeatmapResponse response = realtimeHeatmap(marketDataClient.fetchSectorHeatmap(), LocalDateTime.now());
            sectorHeatmapCache.put(key, new CacheEntry<>(response, expiresAtMillis(ttl)));
            return response;
        } catch (RuntimeException ex) {
            markSectorSourceUnavailable();
            log.warn("sector heatmap source failed, return stale or unavailable data: {}", ex.getMessage());
            return staleOrUnavailableHeatmap(existing, "东方财富板块热力图实时数据获取失败，暂不展示演示数据。");
        }
    }

    @Override
    public SectorHotStocksResponse marketHotStocks(int limit) {
        int size = Math.max(1, Math.min(limit, 20));
        return sectorHotStocksResponse(
                "market:" + size,
                Duration.ofSeconds(properties.getMarket().getSectorHotStocksCacheTtlSeconds()),
                () -> marketDataClient.fetchMarketHotStocks(size),
                "东方财富全市场热门股票实时数据获取失败，暂不展示演示数据。"
        );
    }

    @Override
    public SectorHotStocksResponse sectorHotStocks(String sectorCode, int limit) {
        if (sectorCode == null || sectorCode.isBlank()) {
            return SectorHotStocksResponse.unavailable("板块代码为空，无法获取热门股票。");
        }
        String normalizedCode = sectorCode.trim();
        int size = Math.max(1, Math.min(limit, 20));
        return sectorHotStocksResponse(
                "sector:" + normalizedCode + ":" + size,
                Duration.ofSeconds(properties.getMarket().getSectorHotStocksCacheTtlSeconds()),
                () -> marketDataClient.fetchSectorHotStocks(normalizedCode, size),
                "东方财富板块热门股票实时数据获取失败，暂不展示演示数据。"
        );
    }

    @Override
    public List<IntradayPointResponse> intraday(String symbol) {
        String normalizedCode = normalizeCode(symbol);
        return cachedWithFallback(
                intradayCache,
                normalizedCode,
                Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds()),
                () -> marketDataClient.fetchIntraday(normalizedCode),
                () -> fallbackMarketDataClient.fetchIntraday(normalizedCode)
        );
    }

    @Override
    public List<KlinePointResponse> kline(String symbol, String period, int limit) {
        String normalizedCode = normalizeCode(symbol);
        String normalizedPeriod = period == null || period.isBlank() ? "day" : period.trim();
        int size = Math.max(1, Math.min(limit, 240));
        return cachedWithFallback(
                klineCache,
                normalizedCode + ":" + normalizedPeriod + ":" + size,
                Duration.ofMinutes(5),
                () -> marketDataClient.fetchKline(normalizedCode, normalizedPeriod, size),
                () -> fallbackMarketDataClient.fetchKline(normalizedCode, normalizedPeriod, size)
        );
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
    public StockDetailResponse stockDetailForAnalysis(String code) {
        String normalizedCode = normalizeCode(code);
        StockQuoteResponse quote = marketDataClient.fetchQuote(normalizedCode);
        List<KlinePointResponse> kline = marketDataClient.fetchKline(normalizedCode, "day", 60);
        List<IntradayPointResponse> intraday;
        try {
            intraday = marketDataClient.fetchIntraday(normalizedCode);
        } catch (RuntimeException ex) {
            log.warn("analysis intraday unavailable, continue with quote and kline, code={}", normalizedCode, ex);
            intraday = List.of();
        }
        FinanceSnapshotResponse finance;
        try {
            finance = marketDataClient.fetchFinance(normalizedCode);
        } catch (RuntimeException ex) {
            log.warn("analysis finance unavailable, continue with empty finance, code={}", normalizedCode, ex);
            finance = FinanceSnapshotResponse.empty();
        }
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
        return cachedWithFallback(
                quoteCache,
                normalizedCode,
                Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds()),
                () -> marketDataClient.fetchQuote(normalizedCode),
                () -> fallbackMarketDataClient.fetchQuote(normalizedCode)
        );
    }

    @Override
    public Map<String, StockQuoteResponse> quotes(List<String> codes) {
        Map<String, StockQuoteResponse> result = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return result;
        }

        long now = System.currentTimeMillis();
        long ttlMillis = Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds()).toMillis();
        List<String> missingCodes = codes.stream()
                .map(MarketDataServiceImpl::normalizeCode)
                .filter(code -> !code.isBlank())
                .distinct()
                .filter(code -> {
                    CacheEntry<StockQuoteResponse> existing = quoteCache.get(code);
                    if (existing != null && (ttlMillis <= 0 || existing.expiresAtMillis > now)) {
                        result.put(code, existing.value);
                        return false;
                    }
                    return true;
                })
                .toList();

        if (!missingCodes.isEmpty() && now >= batchQuoteSourceUnavailableUntilMillis) {
            try {
                Map<String, StockQuoteResponse> loadedQuotes = marketDataClient.fetchQuotes(missingCodes);
                long expiresAt = ttlMillis <= 0 ? 0 : System.currentTimeMillis() + ttlMillis;
                for (String code : missingCodes) {
                    StockQuoteResponse quote = loadedQuotes.get(code);
                    if (quote == null) {
                        quote = loadedQuotes.get(stockCodeKey(code));
                    }
                    if (quote != null) {
                        quoteCache.put(code, new CacheEntry<>(quote, expiresAt));
                        result.put(code, quote);
                    }
                }
            } catch (RuntimeException ex) {
                batchQuoteSourceUnavailableUntilMillis = System.currentTimeMillis() + QUOTE_SOURCE_FAILURE_COOLDOWN_MILLIS;
                log.warn("batch market quote source failed, return stale or fallback data, codes={}", missingCodes, ex);
            }
        } else if (!missingCodes.isEmpty()) {
            log.debug("batch market quote source is cooling down, return cached or fallback data, codes={}", missingCodes);
        }

        for (String rawCode : codes) {
            String code = normalizeCode(rawCode);
            if (code.isBlank() || result.containsKey(code)) {
                continue;
            }
            CacheEntry<StockQuoteResponse> stale = quoteCache.get(code);
            if (stale != null) {
                result.put(code, stale.value);
                continue;
            }
            StockQuoteResponse fallbackQuote = fallbackMarketDataClient.fetchQuote(code);
            long fallbackExpiresAt = ttlMillis <= 0 ? 0 : System.currentTimeMillis() + Math.max(ttlMillis, QUOTE_SOURCE_FAILURE_COOLDOWN_MILLIS);
            quoteCache.put(code, new CacheEntry<>(fallbackQuote, fallbackExpiresAt));
            result.put(code, fallbackQuote);
        }
        return result;
    }

    @Override
    public FinanceSnapshotResponse finance(String code) {
        String normalizedCode = normalizeCode(code);
        return cachedWithFallback(
                financeCache,
                normalizedCode,
                Duration.ofSeconds(properties.getMarket().getFinanceCacheTtlSeconds()),
                () -> marketDataClient.fetchFinance(normalizedCode),
                () -> fallbackMarketDataClient.fetchFinance(normalizedCode)
        );
    }

    @Override
    public List<NewsFlashResponse> latestNewsForAnalysis(int limit) {
        int size = Math.max(1, Math.min(limit, 20));
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(36);
            List<NewsFlashResponse> loaded = marketDataClient.fetchLatestNews(size);
            if (loaded == null) {
                return List.of();
            }
            return loaded.stream()
                    .filter(item -> item.publishedAt() != null && !item.publishedAt().isBefore(cutoff))
                    .limit(size)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("analysis realtime news unavailable, forbid model from citing news, limit={}", size, ex);
            return List.of();
        }
    }

    private SectorHotStocksResponse sectorHotStocksResponse(
            String key,
            Duration ttl,
            Supplier<List<SectorHotStockResponse>> loader,
            String failureMessage
    ) {
        CacheEntry<List<SectorHotStockResponse>> existing = hotStocksCache.get(key);
        if (isFresh(existing)) {
            return SectorHotStocksResponse.realtime(existing.value, existing.loadedAt);
        }
        if (isSectorSourceCoolingDown()) {
            return staleOrUnavailableHotStocks(existing, "东方财富热门股票数据源短暂不可用，稍后自动重试。");
        }
        try {
            List<SectorHotStockResponse> loaded = safeList(loader.get());
            LocalDateTime loadedAt = LocalDateTime.now();
            hotStocksCache.put(key, new CacheEntry<>(loaded, expiresAtMillis(ttl), loadedAt));
            return SectorHotStocksResponse.realtime(loaded, loadedAt);
        } catch (RuntimeException ex) {
            markSectorSourceUnavailable();
            log.warn("sector hot stocks source failed, return stale or unavailable data, key={}, error={}", key, ex.getMessage());
            return staleOrUnavailableHotStocks(existing, failureMessage);
        }
    }

    private SectorHeatmapResponse realtimeHeatmap(SectorHeatmapResponse response, LocalDateTime fallbackUpdatedAt) {
        LocalDateTime sourceUpdatedAt = sourceUpdatedAt(response, fallbackUpdatedAt);
        return SectorHeatmapResponse.realtime(safeList(response == null ? null : response.items()), sourceUpdatedAt);
    }

    private SectorHeatmapResponse staleOrUnavailableHeatmap(CacheEntry<SectorHeatmapResponse> existing, String message) {
        if (existing == null) {
            return SectorHeatmapResponse.unavailable(message);
        }
        LocalDateTime sourceUpdatedAt = sourceUpdatedAt(existing.value, existing.loadedAt);
        return SectorHeatmapResponse.stale(safeList(existing.value.items()), sourceUpdatedAt, message);
    }

    private SectorHotStocksResponse staleOrUnavailableHotStocks(CacheEntry<List<SectorHotStockResponse>> existing, String message) {
        if (existing == null) {
            return SectorHotStocksResponse.unavailable(message);
        }
        return SectorHotStocksResponse.stale(safeList(existing.value), existing.loadedAt, message);
    }

    private boolean isSectorSourceCoolingDown() {
        return System.currentTimeMillis() < sectorSourceUnavailableUntilMillis;
    }

    private void markSectorSourceUnavailable() {
        sectorSourceUnavailableUntilMillis = System.currentTimeMillis() + SECTOR_SOURCE_FAILURE_COOLDOWN_MILLIS;
    }

    private static boolean isFresh(CacheEntry<?> entry) {
        return entry != null && entry.expiresAtMillis > System.currentTimeMillis();
    }

    private static long expiresAtMillis(Duration ttl) {
        long ttlMillis = ttl.toMillis();
        return ttlMillis <= 0 ? 0 : System.currentTimeMillis() + ttlMillis;
    }

    private static LocalDateTime sourceUpdatedAt(SectorHeatmapResponse response, LocalDateTime fallbackUpdatedAt) {
        if (response == null) {
            return fallbackUpdatedAt == null ? LocalDateTime.now() : fallbackUpdatedAt;
        }
        if (response.sourceUpdatedAt() != null) {
            return response.sourceUpdatedAt();
        }
        if (response.updatedAt() != null) {
            return response.updatedAt();
        }
        return fallbackUpdatedAt == null ? LocalDateTime.now() : fallbackUpdatedAt;
    }

    private static <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
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

    private static <T> T cachedWithFallback(
            ConcurrentMap<String, CacheEntry<T>> cache,
            String key,
            Duration ttl,
            Supplier<T> loader,
            Supplier<T> fallback
    ) {
        try {
            return cached(cache, key, ttl, loader);
        } catch (RuntimeException ex) {
            CacheEntry<T> existing = cache.get(key);
            if (existing != null) {
                log.warn("market data source failed, return stale cache, key={}", key, ex);
                return existing.value;
            }
            if (fallback == null) {
                log.warn("market data source failed and no fallback is allowed, key={}", key, ex);
                throw ex;
            }
            log.warn("market data source failed, return fallback data, key={}", key, ex);
            T fallbackValue = fallback.get();
            long ttlMillis = ttl.toMillis();
            if (ttlMillis > 0) {
                cache.put(key, new CacheEntry<>(fallbackValue, System.currentTimeMillis() + ttlMillis));
            }
            return fallbackValue;
        }
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim();
    }

    private static String stockCodeKey(String code) {
        String normalized = normalizeCode(code).toLowerCase();
        if ((normalized.startsWith("sh") || normalized.startsWith("sz")) && normalized.length() > 2) {
            return normalized.substring(2);
        }
        if (normalized.endsWith(".sh") || normalized.endsWith(".sz")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
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

    private record CacheEntry<T>(T value, long expiresAtMillis, LocalDateTime loadedAt) {
        private CacheEntry(T value, long expiresAtMillis) {
            this(value, expiresAtMillis, LocalDateTime.now());
        }
    }
}
