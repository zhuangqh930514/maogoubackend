package com.maogou.stock.service.impl;

import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.home.HomeOverviewResponse;
import com.maogou.stock.dto.market.MarketBreadthResponse;
import com.maogou.stock.dto.market.MarketIndexResponse;
import com.maogou.stock.dto.market.NewsFlashResponse;
import com.maogou.stock.dto.market.SectorHotStocksResponse;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.HomeOverviewService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.WatchlistService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public class HomeOverviewServiceImpl implements HomeOverviewService {

    private static final Logger log = LoggerFactory.getLogger(HomeOverviewServiceImpl.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final long SECTION_TIMEOUT_MS = 1500;

    private final MarketDataService marketDataService;
    private final WatchlistService watchlistService;
    private final AiAnalysisService aiAnalysisService;
    private final ExecutorService overviewExecutor;

    public HomeOverviewServiceImpl(
            MarketDataService marketDataService,
            WatchlistService watchlistService,
            AiAnalysisService aiAnalysisService
    ) {
        this.marketDataService = marketDataService;
        this.watchlistService = watchlistService;
        this.aiAnalysisService = aiAnalysisService;
        this.overviewExecutor = new ThreadPoolExecutor(
                4, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable, "home-overview-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Override
    public HomeOverviewResponse overview() {
        Map<String, String> warnings = new ConcurrentHashMap<>();
        CompletableFuture<List<NewsFlashResponse>> news = async(
                "news", () -> marketDataService.latestNews(50), List.of(), warnings);
        CompletableFuture<List<MarketIndexResponse>> indexes = async(
                "indexes", marketDataService::coreIndexes, List.of(), warnings);
        CompletableFuture<MarketBreadthResponse> breadth = async(
                "breadth", marketDataService::marketBreadth, null, warnings);
        CompletableFuture<SectorHotStocksResponse> hotStocks = async(
                "hotStocks", () -> marketDataService.marketHotStocks(10),
                SectorHotStocksResponse.unavailable("市场热度数据暂不可用"), warnings);

        List<String> watchlistCodes = safe(
                "watchlistCodes", () -> watchlistService.codes(null), List.of(), warnings);
        AiAnalysisReportResponse latestAiReport = safe(
                "latestAiReport", () -> aiAnalysisService.latestReport(null), null, warnings);

        return new HomeOverviewResponse(
                news.join(),
                indexes.join(),
                breadth.join(),
                hotStocks.join(),
                latestAiReport,
                watchlistCodes,
                Map.copyOf(warnings),
                LocalDateTime.now());
    }

    private <T> CompletableFuture<T> async(
            String section,
            Supplier<T> supplier,
            T fallback,
            Map<String, String> warnings
    ) {
        return CompletableFuture.supplyAsync(
                        () -> safe(section, supplier, fallback, warnings), overviewExecutor)
                .orTimeout(SECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(exception -> {
                    warnings.putIfAbsent(section, "首页数据源响应超时");
                    log.warn("home overview section timed out, section={}", section);
                    return fallback;
                });
    }

    private <T> T safe(String section, Supplier<T> supplier, T fallback, Map<String, String> warnings) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "数据获取失败" : exception.getMessage();
            warnings.put(section, message);
            log.warn("home overview section failed, section={}, message={}", section, message);
            return fallback;
        }
    }

    @PreDestroy
    public void shutdown() {
        overviewExecutor.shutdown();
    }
}
