package com.maogou.stock.service.impl;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.MarketQuoteCurrent;
import com.maogou.stock.dto.market.*;
import com.maogou.stock.infrastructure.market.MarketDataClient;
import com.maogou.stock.infrastructure.market.ResearchMarketDataClient;
import com.maogou.stock.infrastructure.market.ResearchSourceResult;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.MarketSnapshotService;
import com.maogou.stock.mapper.MarketQuoteCurrentMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Service
public class MarketDataServiceImpl implements MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataServiceImpl.class);
    private static final long QUOTE_SOURCE_FAILURE_COOLDOWN_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final long SECTOR_SOURCE_FAILURE_COOLDOWN_MILLIS = Duration.ofMinutes(3).toMillis();
    private static final long BREADTH_SOURCE_FAILURE_COOLDOWN_MILLIS = Duration.ofMinutes(2).toMillis();

    private final MarketDataClient marketDataClient;
    private final AppProperties properties;
    private final ResearchMarketDataClient researchMarketDataClient;
    private final MarketSnapshotService marketSnapshotService;
    private final MarketQuoteCurrentMapper marketQuoteCurrentMapper;
    private final ConcurrentMap<String, CacheEntry<StockQuoteResponse>> quoteCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<FinanceSnapshotResponse>> financeCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<NewsFlashResponse>>> newsCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<MarketIndexResponse>>> indexCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<MarketBreadthResponse>> breadthCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<SectorHeatmapResponse>> sectorHeatmapCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<SectorHotStockResponse>>> hotStocksCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<IntradayPointResponse>>> intradayCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<List<KlinePointResponse>>> klineCache = new ConcurrentHashMap<>();
    private final Set<String> pendingFastQuoteCodes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean fastQuoteRefreshRunning = new AtomicBoolean();
    private final ExecutorService fastQuoteRefreshExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "market-quote-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long batchQuoteSourceUnavailableUntilMillis = 0;
    private volatile long sectorSourceUnavailableUntilMillis = 0;
    private volatile long breadthSourceUnavailableUntilMillis = 0;

    public MarketDataServiceImpl(MarketDataClient marketDataClient, AppProperties properties) {
        this(marketDataClient, properties, null, null);
    }

    public MarketDataServiceImpl(
            MarketDataClient marketDataClient,
            AppProperties properties,
            ResearchMarketDataClient researchMarketDataClient
    ) {
        this(marketDataClient, properties, researchMarketDataClient, null, null);
    }

    public MarketDataServiceImpl(
            MarketDataClient marketDataClient,
            AppProperties properties,
            ResearchMarketDataClient researchMarketDataClient,
            MarketSnapshotService marketSnapshotService
    ) {
        this(marketDataClient, properties, researchMarketDataClient, marketSnapshotService, null);
    }

    @Autowired
    public MarketDataServiceImpl(
            MarketDataClient marketDataClient,
            AppProperties properties,
            ResearchMarketDataClient researchMarketDataClient,
            MarketSnapshotService marketSnapshotService,
            MarketQuoteCurrentMapper marketQuoteCurrentMapper
    ) {
        this.marketDataClient = marketDataClient;
        this.properties = properties;
        this.researchMarketDataClient = researchMarketDataClient;
        this.marketSnapshotService = marketSnapshotService;
        this.marketQuoteCurrentMapper = marketQuoteCurrentMapper;
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
        return cachedOrEmpty(
                newsCache,
                "latest:" + size,
                Duration.ofMinutes(5),
                () -> marketDataClient.fetchLatestNews(size),
                List.of()
        );
    }

    @Override
    public List<MarketIndexResponse> coreIndexes() {
        return cachedOrEmpty(
                indexCache,
                "core-indexes",
                Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds()),
                marketDataClient::fetchCoreIndexes,
                List.of()
        );
    }

    @Override
    public synchronized MarketBreadthResponse marketBreadth() {
        String key = "market-breadth";
        Duration ttl = Duration.ofSeconds(30);
        CacheEntry<MarketBreadthResponse> existing = breadthCache.get(key);
        if (isFresh(existing)) {
            return MarketBreadthResponse.realtime(existing.value, breadthSourceUpdatedAt(existing));
        }
        if (System.currentTimeMillis() < breadthSourceUnavailableUntilMillis) {
            return staleOrUnavailableBreadth(existing, "东方财富涨跌分布数据源暂时不可用，系统将在稍后自动重试。");
        }
        try {
            MarketBreadthResponse loaded = marketDataClient.fetchMarketBreadth();
            if (loaded == null || loaded.buckets() == null || loaded.buckets().isEmpty()) {
                throw new IllegalStateException("东方财富涨跌分布返回为空");
            }
            LocalDateTime sourceUpdatedAt = breadthSourceUpdatedAt(loaded, LocalDateTime.now());
            breadthCache.put(key, new CacheEntry<>(loaded, expiresAtMillis(ttl), sourceUpdatedAt));
            breadthSourceUnavailableUntilMillis = 0;
            return MarketBreadthResponse.realtime(loaded, sourceUpdatedAt);
        } catch (RuntimeException ex) {
            breadthSourceUnavailableUntilMillis = System.currentTimeMillis() + BREADTH_SOURCE_FAILURE_COOLDOWN_MILLIS;
            log.warn("market breadth source failed, return stale or unavailable data, error={}", ex.getMessage(), ex);
            return staleOrUnavailableBreadth(existing, "东方财富涨跌分布获取失败：" + readableMessage(ex));
        }
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
        return cachedOrEmpty(
                intradayCache,
                normalizedCode,
                Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds()),
                () -> marketDataClient.fetchIntraday(normalizedCode),
                List.of()
        );
    }

    @Override
    public List<KlinePointResponse> kline(String symbol, String period, int limit) {
        String normalizedCode = normalizeCode(symbol);
        String normalizedPeriod = period == null || period.isBlank() ? "day" : period.trim();
        int size = Math.max(1, Math.min(limit, 240));
        return cachedOrEmpty(
                klineCache,
                normalizedCode + ":" + normalizedPeriod + ":" + size,
                Duration.ofMinutes(5),
                () -> marketDataClient.fetchKline(normalizedCode, normalizedPeriod, size),
                List.of()
        );
    }

    @Override
    public KlineSeriesSnapshot klineAt(String symbol, String period, int limit, LocalDateTime asOfTime) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("K 线 asOfTime 不能为空");
        }
        String normalizedCode = normalizeCode(symbol);
        String normalizedPeriod = period == null || period.isBlank() ? "day" : period.trim();
        int size = Math.max(1, Math.min(limit, 240));
        if (researchMarketDataClient == null) {
            return marketDataClient.fetchKlineAt(normalizedCode, normalizedPeriod, size, asOfTime);
        }
        ResearchSourceResult<KlineSeriesSnapshot> result = researchMarketDataClient.fetchKlineAt(
                normalizedCode, normalizedPeriod, size, asOfTime);
        if (!result.formalReady()) {
            throw new IllegalStateException("正式研究 K 线不可用：" + result.qualityStatus()
                    + "，" + result.message() + "；" + providerAttemptSummary(result));
        }
        return result.data();
    }

    private static String providerAttemptSummary(ResearchSourceResult<?> result) {
        if (result == null || result.attempts() == null || result.attempts().isEmpty()) {
            return "来源尝试：无可用来源，可能仍处于熔断冷却期";
        }
        return "来源尝试：" + result.attempts().stream().map(attempt -> {
            String reason = attempt.errorMessage() == null || attempt.errorMessage().isBlank()
                    ? attempt.status().name() : attempt.errorMessage();
            return attempt.providerCode() + "/" + attempt.endpointType() + "：" + reason;
        }).reduce((left, right) -> left + "；" + right).orElse("无");
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
    public StockDetailResponse stockDetailAt(String code, LocalDateTime asOfTime) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("个股详情 asOfTime 不能为空");
        }
        String normalizedCode = normalizeCode(code);
        KlineSeriesSnapshot series = klineAt(normalizedCode, "day", 60, asOfTime);
        if (series == null || series.points() == null || series.points().isEmpty()
                || !series.fingerprintMatches()
                || !"NONE".equalsIgnoreCase(series.adjustmentMode())) {
            throw new IllegalStateException("时点化未复权 K 线不可用：" + normalizedCode);
        }
        List<KlinePointResponse> points = series.points().stream()
                .filter(item -> item != null && item.tradeDate() != null
                        && !item.tradeDate().isAfter(asOfTime.toLocalDate()))
                .toList();
        if (points.isEmpty()) {
            throw new IllegalStateException("指定时点前没有可用 K 线：" + normalizedCode);
        }
        KlinePointResponse latest = points.get(points.size() - 1);
        KlinePointResponse previous = points.size() > 1 ? points.get(points.size() - 2) : null;
        BigDecimal close = latest.close();
        if (close == null || close.signum() <= 0) {
            throw new IllegalStateException("指定时点收盘价无效：" + normalizedCode);
        }
        BigDecimal previousClose = previous == null || previous.close() == null
                ? close : previous.close();
        BigDecimal change = close.subtract(previousClose);
        BigDecimal percent = previousClose.signum() == 0
                ? BigDecimal.ZERO
                : change.multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 4, java.math.RoundingMode.HALF_UP);
        StockQuoteResponse quote = new StockQuoteResponse(
                normalizedCode,
                normalizedCode,
                close,
                change,
                percent,
                volumeRatio(points),
                normalizedCode.startsWith("6") ? "SH" : "SZ",
                series.source(),
                asOfTime);
        FinanceSnapshotResponse finance;
        try {
            finance = financeAt(normalizedCode, asOfTime);
        } catch (RuntimeException exception) {
            log.warn("point-in-time finance unavailable, continue with empty finance, code={}, asOf={}",
                    normalizedCode, asOfTime, exception);
            finance = FinanceSnapshotResponse.empty();
        }
        return new StockDetailResponse(
                quote,
                finance,
                List.of(),
                points,
                adviceByPercent(percent),
                scoreByPercent(percent));
    }

    @Override
    public StockQuoteResponse quote(String code) {
        String normalizedCode = normalizeCode(code);
        Duration ttl = Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds());
        CacheEntry<StockQuoteResponse> existing = quoteCache.get(normalizedCode);
        if (isFresh(existing)) {
            return existing.value;
        }
        try {
            StockQuoteResponse loaded = normalizeQuote(marketDataClient.fetchQuote(normalizedCode), normalizedCode);
            quoteCache.put(normalizedCode, new CacheEntry<>(loaded, expiresAtMillis(ttl), loaded.sourceAsOf()));
            persistRealtimeQuote(loaded);
            return loaded;
        } catch (RuntimeException ex) {
            String message = "行情获取失败：" + readableMessage(ex);
            if (existing != null && existing.value != null && existing.value.hasUsablePrice()) {
                log.warn("market quote source failed, return stale real cache, code={}, error={}", normalizedCode, message);
                return StockQuoteResponse.stale(existing.value, message);
            }
            log.warn("market quote unavailable and no real cache, code={}, error={}", normalizedCode, message);
            return StockQuoteResponse.unavailable(normalizedCode, null, message);
        }
    }

    private static BigDecimal volumeRatio(List<KlinePointResponse> points) {
        if (points == null || points.size() < 2) {
            return null;
        }
        KlinePointResponse latest = points.get(points.size() - 1);
        if (latest.volume() == null || latest.volume() <= 0) {
            return null;
        }
        int start = Math.max(0, points.size() - 6);
        List<Long> history = points.subList(start, points.size() - 1).stream()
                .map(KlinePointResponse::volume)
                .filter(value -> value != null && value > 0)
                .toList();
        if (history.isEmpty()) {
            return null;
        }
        BigDecimal average = history.stream().map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(history.size()), 8, java.math.RoundingMode.HALF_UP);
        return BigDecimal.valueOf(latest.volume()).divide(average, 4, java.math.RoundingMode.HALF_UP);
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
                if (loadedQuotes == null) {
                    throw new IllegalStateException("批量行情来源返回空结果");
                }
                long expiresAt = ttlMillis <= 0 ? 0 : System.currentTimeMillis() + ttlMillis;
                for (String code : missingCodes) {
                    StockQuoteResponse quote = loadedQuotes.get(code);
                    if (quote == null) {
                        quote = loadedQuotes.get(stockCodeKey(code));
                    }
                    if (quote != null) {
                        StockQuoteResponse normalized = normalizeQuote(quote, code);
                        quoteCache.put(code, new CacheEntry<>(normalized, expiresAt, normalized.sourceAsOf()));
                        persistRealtimeQuote(normalized);
                        result.put(code, normalized);
                    }
                }
            } catch (RuntimeException ex) {
                batchQuoteSourceUnavailableUntilMillis = System.currentTimeMillis() + QUOTE_SOURCE_FAILURE_COOLDOWN_MILLIS;
                log.warn("batch market quote source failed, return stale or unavailable data, codes={}, error={}", missingCodes, readableMessage(ex));
            }
        } else if (!missingCodes.isEmpty()) {
            log.debug("batch market quote source is cooling down, return cached or unavailable data, codes={}", missingCodes);
        }

        for (String rawCode : codes) {
            String code = normalizeCode(rawCode);
            if (code.isBlank() || result.containsKey(code)) {
                continue;
            }
            CacheEntry<StockQuoteResponse> stale = quoteCache.get(code);
            if (stale != null && stale.value != null && stale.value.hasUsablePrice()) {
                result.put(code, StockQuoteResponse.stale(stale.value, "行情来源暂时不可用，展示上次真实行情。"));
                continue;
            }
            if (stale != null && stale.value != null && !stale.value.hasUsablePrice()) {
                result.put(code, stale.value);
                continue;
            }
            if (result.containsKey(code)) {
                continue;
            }
            if (stale != null) {
                result.put(code, stale.value);
                continue;
            }
            result.put(code, StockQuoteResponse.unavailable(code, null, "行情来源暂时不可用，暂无真实快照。"));
        }
        return result;
    }

    @Override
    public Map<String, StockQuoteResponse> quotesFast(List<String> codes) {
        Map<String, StockQuoteResponse> cachedQuotes = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return cachedQuotes;
        }
        long now = System.currentTimeMillis();
        List<String> normalizedCodes = codes.stream()
                .map(MarketDataServiceImpl::normalizeCode)
                .filter(code -> !code.isBlank())
                .distinct()
                .toList();
        List<String> refreshCodes = normalizedCodes.stream()
                .filter(code -> {
                    CacheEntry<StockQuoteResponse> cached = quoteCache.get(code);
                    if (cached != null) {
                        boolean fresh = cached.expiresAtMillis <= 0 || cached.expiresAtMillis > now;
                        if (fresh || cached.value == null || !cached.value.hasUsablePrice()) {
                            cachedQuotes.put(code, cached.value);
                        } else {
                            cachedQuotes.put(code, StockQuoteResponse.stale(
                                    cached.value, "行情来源正在刷新，展示上次真实行情。"));
                        }
                    }
                    return cached == null || cached.expiresAtMillis <= now;
                })
                .toList();
        if (!refreshCodes.isEmpty() && marketQuoteCurrentMapper != null) {
            try {
                List<MarketQuoteCurrent> persisted = marketQuoteCurrentMapper.selectBySymbols(refreshCodes);
                Map<String, MarketQuoteCurrent> byCode = new LinkedHashMap<>();
                for (MarketQuoteCurrent current : persisted == null ? List.<MarketQuoteCurrent>of() : persisted) {
                    if (current != null && current.symbol != null) {
                        byCode.putIfAbsent(current.symbol, current);
                    }
                }
                long persistentNow = System.currentTimeMillis();
                long ttlMillis = Duration.ofSeconds(properties.getMarket().getQuoteCacheTtlSeconds()).toMillis();
                for (String code : refreshCodes) {
                    MarketQuoteCurrent current = byCode.get(code);
                    if (current == null) {
                        continue;
                    }
                    StockQuoteResponse quote = fromPersistentCurrent(current, ttlMillis);
                    boolean fresh = current.sourceAsOf != null && (ttlMillis <= 0
                            || persistentNow - current.sourceAsOf.atZone(java.time.ZoneId.systemDefault())
                            .toInstant().toEpochMilli() <= ttlMillis);
                    quoteCache.put(code, new CacheEntry<>(quote,
                            fresh ? persistentNow + Math.max(1000, ttlMillis) : persistentNow, current.sourceAsOf));
                    cachedQuotes.put(code, quote);
                }
            } catch (RuntimeException exception) {
                log.warn("读取持久化行情快照失败，继续使用进程内缓存，codes={}", refreshCodes, exception);
            }
        }
        scheduleFastQuoteRefresh(refreshCodes);
        return cachedQuotes;
    }

    private StockQuoteResponse fromPersistentCurrent(MarketQuoteCurrent current, long ttlMillis) {
        LocalDateTime sourceAsOf = current.sourceAsOf;
        boolean stale = sourceAsOf == null || (ttlMillis > 0
                && Duration.between(sourceAsOf, LocalDateTime.now()).toMillis() > ttlMillis);
        String status = stale ? "STALE" : current.sourceStatus == null ? "UNAVAILABLE" : current.sourceStatus;
        String mode = stale ? "RECENT_CLOSE" : current.dataMode == null ? "UNAVAILABLE" : current.dataMode;
        String message = stale ? "展示最近一次真实行情快照，正在后台刷新" : "来自真实行情快照";
        return new StockQuoteResponse(current.symbol, current.name, current.latestPrice,
                current.changeAmount, current.changePercent, current.volumeRatio, current.market,
                current.sourceProvider, sourceAsOf, status, mode, "CLOSED", current.tradeDate,
                sourceAsOf, LocalDateTime.now(), message);
    }

    private void scheduleFastQuoteRefresh(List<String> codes) {
        if (codes != null) {
            pendingFastQuoteCodes.addAll(codes);
        }
        if (pendingFastQuoteCodes.isEmpty() || !fastQuoteRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            fastQuoteRefreshExecutor.execute(this::drainFastQuoteRefreshes);
        } catch (RejectedExecutionException exception) {
            fastQuoteRefreshRunning.set(false);
            log.debug("fast quote refresh executor rejected task", exception);
        }
    }

    private void drainFastQuoteRefreshes() {
        try {
            while (!pendingFastQuoteCodes.isEmpty()) {
                List<String> codes = List.copyOf(pendingFastQuoteCodes);
                pendingFastQuoteCodes.removeAll(codes);
                try {
                    quotes(codes);
                } catch (RuntimeException exception) {
                    log.warn("background quote refresh failed, codes={}", codes, exception);
                }
            }
        } finally {
            fastQuoteRefreshRunning.set(false);
            if (!pendingFastQuoteCodes.isEmpty()) {
                scheduleFastQuoteRefresh(List.of());
            }
        }
    }

    @PreDestroy
    void shutdownFastQuoteRefreshExecutor() {
        fastQuoteRefreshExecutor.shutdownNow();
    }

    @Override
    public FinanceSnapshotResponse finance(String code) {
        String normalizedCode = normalizeCode(code);
        return cachedOrEmpty(
                financeCache,
                normalizedCode,
                Duration.ofSeconds(properties.getMarket().getFinanceCacheTtlSeconds()),
                () -> marketDataClient.fetchFinance(normalizedCode),
                FinanceSnapshotResponse.empty()
        );
    }

    @Override
    public FinanceSnapshotResponse financeAt(String code, LocalDateTime asOfTime) {
        String normalizedCode = normalizeCode(code);
        if (asOfTime == null) {
            throw new IllegalArgumentException("财务快照 asOfTime 不能为空");
        }
        return marketDataClient.fetchFinanceAt(normalizedCode, asOfTime);
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

    @Override
    public List<NewsFlashResponse> latestNewsForAnalysisAt(int limit, LocalDateTime asOfTime) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("资讯 asOfTime 不能为空");
        }
        int size = Math.max(1, Math.min(limit, 20));
        try {
            LocalDateTime cutoff = asOfTime.minusHours(36);
            List<NewsFlashResponse> loaded = marketDataClient.fetchLatestNews(Math.max(size, 20));
            if (loaded == null) {
                return List.of();
            }
            return loaded.stream()
                    .filter(item -> item.publishedAt() != null
                            && !item.publishedAt().isAfter(asOfTime)
                            && !item.publishedAt().isBefore(cutoff))
                    .limit(size)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("point-in-time news unavailable, forbid model from citing news, asOf={}", asOfTime, exception);
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
            if (loaded.isEmpty()) {
                throw new IllegalStateException("东方财富热门股票来源返回空结果");
            }
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
        if (response == null || response.items() == null || response.items().isEmpty()) {
            throw new IllegalStateException("东方财富板块热力图来源返回空结果");
        }
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

    private static LocalDateTime breadthSourceUpdatedAt(CacheEntry<MarketBreadthResponse> entry) {
        if (entry == null) {
            return null;
        }
        return breadthSourceUpdatedAt(entry.value, entry.loadedAt);
    }

    private static LocalDateTime breadthSourceUpdatedAt(MarketBreadthResponse response, LocalDateTime fallbackUpdatedAt) {
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

    private static MarketBreadthResponse staleOrUnavailableBreadth(
            CacheEntry<MarketBreadthResponse> existing,
            String message
    ) {
        if (existing == null || existing.value == null || existing.value.buckets() == null
                || existing.value.buckets().isEmpty()) {
            return MarketBreadthResponse.unavailable(message);
        }
        return MarketBreadthResponse.stale(
                existing.value,
                breadthSourceUpdatedAt(existing),
                message);
    }

    private static String readableMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
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

    private static <T> T cachedOrEmpty(
            ConcurrentMap<String, CacheEntry<T>> cache,
            String key,
            Duration ttl,
            Supplier<T> loader,
            T emptyValue
    ) {
        try {
            return cached(cache, key, ttl, loader);
        } catch (RuntimeException ex) {
            CacheEntry<T> existing = cache.get(key);
            if (existing != null && existing.value != null) {
                log.warn("market data source failed, return stale cache, key={}, error={}", key, readableMessage(ex));
                return existing.value;
            }
            log.warn("market data source failed and no real cache, return unavailable value, key={}, error={}",
                    key, readableMessage(ex));
            return emptyValue;
        }
    }

    private StockQuoteResponse normalizeQuote(StockQuoteResponse quote, String requestedCode) {
        if (quote == null) {
            throw new IllegalStateException("行情来源返回空报价");
        }
        LocalDateTime sourceAsOf = quote.sourceAsOf() != null
                ? quote.sourceAsOf() : quote.fetchedAt();
        if (sourceAsOf == null) {
            sourceAsOf = LocalDateTime.now();
        }
        String source = quote.source() == null || quote.source().isBlank() ? "UNKNOWN" : quote.source();
        String code = quote.code() == null || quote.code().isBlank() ? requestedCode : quote.code();
        LocalDate tradeDate = quote.tradeDate() == null ? sourceAsOf.toLocalDate() : quote.tradeDate();
        if ("MOCK".equalsIgnoreCase(source) || "LOCAL_FALLBACK".equalsIgnoreCase(source)) {
            return new StockQuoteResponse(code, quote.name(), quote.price(), quote.change(), quote.percent(),
                    quote.volumeRatio(), quote.market(), source, quote.fetchedAt(), "UNAVAILABLE", "UNAVAILABLE",
                    "CLOSED", tradeDate, sourceAsOf, LocalDateTime.now(), "演示行情源，仅允许开发测试，不是正式行情");
        }
        if (quote.price() == null || quote.price().signum() <= 0) {
            throw new IllegalStateException("行情来源返回无效价格：" + code);
        }
        String status = quote.sourceStatus() == null || quote.sourceStatus().isBlank()
                ? "REALTIME" : quote.sourceStatus();
        String mode = quote.dataMode() == null || quote.dataMode().isBlank() ? "LIVE" : quote.dataMode();
        String session = quote.marketSession() == null || quote.marketSession().isBlank()
                ? "TRADING" : quote.marketSession();
        return new StockQuoteResponse(code, quote.name(), quote.price(), quote.change(), quote.percent(),
                quote.volumeRatio(), quote.market(), source, quote.fetchedAt(), status, mode, session,
                tradeDate, sourceAsOf, LocalDateTime.now(), quote.message());
    }

    private void persistRealtimeQuote(StockQuoteResponse quote) {
        if (marketSnapshotService == null || quote == null
                || !"REALTIME".equalsIgnoreCase(quote.sourceStatus())) {
            return;
        }
        try {
            marketSnapshotService.recordRealtimeQuote(quote);
        } catch (RuntimeException exception) {
            log.error("真实行情快照写入失败，stockCode={}, provider={}, reason={}",
                    quote.code(), quote.source(), readableMessage(exception), exception);
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
        if (percent == null) {
            return "数据不可用";
        }
        if (percent.compareTo(new BigDecimal("3")) >= 0) {
            return "突破跟踪";
        }
        if (percent.compareTo(BigDecimal.ZERO) < 0) {
            return "控制仓位";
        }
        return "稳健持有";
    }

    private static int scoreByPercent(BigDecimal percent) {
        if (percent == null) {
            return 0;
        }
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
