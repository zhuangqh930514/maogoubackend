package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.dto.watchlist.AddWatchStockRequest;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.WatchlistService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class WatchlistServiceImpl implements WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistServiceImpl.class);
    private static final FinanceSnapshotResponse EMPTY_FINANCE = FinanceSnapshotResponse.empty();

    private final WatchStockMapper watchStockMapper;
    private final MarketDataService marketDataService;
    private final ExecutorService listExecutor = Executors.newFixedThreadPool(
            Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors())),
            new WatchlistThreadFactory()
    );

    public WatchlistServiceImpl(WatchStockMapper watchStockMapper, MarketDataService marketDataService) {
        this.watchStockMapper = watchStockMapper;
        this.marketDataService = marketDataService;
    }

    @Override
    public List<WatchStockResponse> list(String groupName) {
        QueryWrapper<WatchStock> wrapper = new QueryWrapper<WatchStock>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .orderByAsc("priority")
                .orderByDesc("created_at");
        if (groupName != null && !groupName.isBlank() && !"全部".equals(groupName)) {
            wrapper.eq("group_name", groupName);
        }
        List<WatchStock> stocks = watchStockMapper.selectList(wrapper);
        List<CompletableFuture<WatchStockResponse>> futures = stocks.stream()
                .map(entity -> CompletableFuture.supplyAsync(() -> toResponseSafely(entity), listExecutor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    @PreDestroy
    public void shutdown() {
        listExecutor.shutdown();
    }

    @Override
    @Transactional
    public WatchStockResponse add(AddWatchStockRequest request) {
        Long userId = AuthContext.currentUserIdOrDefault();
        String code = request.code().trim();
        String groupName = request.groupName() == null || request.groupName().isBlank() ? "全部" : request.groupName();
        WatchStock existing = watchStockMapper.selectAnyByUserIdAndCode(userId, code);
        if (existing != null && existing.deleted != null && existing.deleted == 0) {
            return toResponse(existing);
        }

        StockQuoteResponse quote = marketDataService.quote(code);
        if (existing != null) {
            existing.stockName = quote.name();
            existing.market = quote.market();
            existing.groupName = groupName;
            existing.priority = existing.priority == null ? 100 : existing.priority;
            existing.deleted = 0;
            existing.updatedAt = LocalDateTime.now();
            watchStockMapper.restore(existing);
            return toResponse(existing);
        }

        WatchStock entity = new WatchStock();
        entity.userId = userId;
        entity.stockCode = code;
        entity.stockName = quote.name();
        entity.market = quote.market();
        entity.groupName = groupName;
        entity.priority = 100;
        entity.deleted = 0;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = entity.createdAt;
        try {
            watchStockMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            WatchStock concurrentExisting = watchStockMapper.selectAnyByUserIdAndCode(userId, code);
            if (concurrentExisting != null) {
                return toResponse(concurrentExisting);
            }
            throw ex;
        }
        return toResponse(entity);
    }

    @Override
    @Transactional
    public void remove(String code) {
        watchStockMapper.delete(new QueryWrapper<WatchStock>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .eq("stock_code", code));
    }

    @Override
    @Transactional
    public void removeBatch(List<String> codes) {
        List<String> normalizedCodes = normalizeCodes(codes);
        if (normalizedCodes.isEmpty()) {
            return;
        }
        watchStockMapper.delete(new QueryWrapper<WatchStock>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .in("stock_code", normalizedCodes));
    }

    @Override
    @Transactional
    public void reorder(List<String> codes) {
        List<String> normalizedCodes = normalizeCodes(codes);
        Long userId = AuthContext.currentUserIdOrDefault();
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < normalizedCodes.size(); index++) {
            WatchStock entity = new WatchStock();
            entity.userId = userId;
            entity.stockCode = normalizedCodes.get(index);
            entity.priority = (index + 1) * 10;
            entity.updatedAt = now;
            watchStockMapper.updatePriority(entity);
        }
    }

    private WatchStockResponse toResponse(WatchStock entity) {
        StockQuoteResponse quote = marketDataService.quote(entity.stockCode);
        FinanceSnapshotResponse finance = marketDataService.finance(entity.stockCode);
        return buildResponse(entity, quote, finance);
    }

    private WatchStockResponse toResponseSafely(WatchStock entity) {
        try {
            return toResponse(entity);
        } catch (RuntimeException ex) {
            log.warn("load watch stock market data failed, stockCode={}", entity.stockCode, ex);
            StockQuoteResponse quote = new StockQuoteResponse(
                    entity.stockCode,
                    entity.stockName == null ? entity.stockCode : entity.stockName,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    entity.market
            );
            return buildResponse(entity, quote, EMPTY_FINANCE, "行情暂不可用", 0);
        }
    }

    private WatchStockResponse buildResponse(WatchStock entity, StockQuoteResponse quote, FinanceSnapshotResponse finance) {
        int aiScore = quote.percent().signum() < 0 ? 64 : quote.percent().doubleValue() >= 3 ? 86 : 72;
        String advice = quote.percent().signum() < 0 ? "控制仓位" : quote.percent().doubleValue() >= 3 ? "突破跟踪" : "稳健持有";
        return buildResponse(entity, quote, finance, advice, aiScore);
    }

    private WatchStockResponse buildResponse(
            WatchStock entity,
            StockQuoteResponse quote,
            FinanceSnapshotResponse finance,
            String advice,
            int aiScore
    ) {
        return new WatchStockResponse(
                entity.id,
                entity.stockCode,
                entity.stockName == null ? quote.name() : entity.stockName,
                quote.price(),
                quote.percent(),
                quote.volumeRatio(),
                aiScore,
                advice,
                finance.pe(),
                finance.pb(),
                finance.revenueGrowth(),
                finance.profitGrowth(),
                entity.groupName
        );
    }

    private static List<String> normalizeCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static class WatchlistThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "watchlist-market-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
