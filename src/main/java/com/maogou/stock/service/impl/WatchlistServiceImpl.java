package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.dto.watchlist.AddWatchStockRequest;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.dto.common.PageResponse;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class WatchlistServiceImpl implements WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistServiceImpl.class);
    private static final FinanceSnapshotResponse EMPTY_FINANCE = FinanceSnapshotResponse.empty();

    private final WatchStockMapper watchStockMapper;
    private final MarketDataService marketDataService;

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
        Map<String, StockQuoteResponse> quotes = marketDataService.quotesFast(stocks.stream()
                .map(entity -> entity.stockCode)
                .toList());
        return stocks.stream()
                .map(entity -> buildLightResponse(entity, quotes.get(entity.stockCode)))
                .toList();
    }

    @Override
    public PageResponse<WatchStockResponse> page(String view, int page, int pageSize) {
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 100));
        String normalizedView = view == null || view.isBlank() ? "全部" : view.trim();
        if ("全部".equals(normalizedView)) {
            return pageAll(normalizedPageSize, page);
        }

        List<WatchStockResponse> filtered = list(null).stream()
                .filter(item -> matchesView(item, normalizedView))
                .toList();
        int totalPages = filtered.isEmpty() ? 0 : (int) Math.ceil((double) filtered.size() / normalizedPageSize);
        int normalizedPage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        int fromIndex = Math.min((normalizedPage - 1) * normalizedPageSize, filtered.size());
        int toIndex = Math.min(fromIndex + normalizedPageSize, filtered.size());
        return PageResponse.of(filtered.subList(fromIndex, toIndex), filtered.size(), normalizedPage, normalizedPageSize);
    }

    private PageResponse<WatchStockResponse> pageAll(int pageSize, int page) {
        Long userId = AuthContext.currentUserIdOrDefault();
        QueryWrapper<WatchStock> countQuery = new QueryWrapper<WatchStock>().eq("user_id", userId);
        long total = watchStockMapper.selectCount(countQuery);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        int normalizedPage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        if (total == 0) {
            return PageResponse.of(List.of(), 0, normalizedPage, pageSize);
        }

        long offset = (long) (normalizedPage - 1) * pageSize;
        QueryWrapper<WatchStock> pageQuery = new QueryWrapper<WatchStock>()
                .eq("user_id", userId)
                .orderByAsc("priority")
                .orderByDesc("created_at")
                .last("LIMIT " + pageSize + " OFFSET " + offset);
        List<WatchStock> stocks = watchStockMapper.selectList(pageQuery);
        Map<String, StockQuoteResponse> quotes = marketDataService.quotesFast(stocks.stream()
                .map(entity -> entity.stockCode)
                .toList());
        List<WatchStockResponse> items = stocks.stream()
                .map(entity -> buildLightResponse(entity, quotes.get(entity.stockCode)))
                .toList();
        return PageResponse.of(items, total, normalizedPage, pageSize);
    }

    private static boolean matchesView(WatchStockResponse item, String view) {
        return switch (view) {
            case "AI重点" -> item.aiScore() != null && item.aiScore() >= 78;
            case "高波动" -> item.volumeRatio() != null && item.volumeRatio().compareTo(new BigDecimal("1.8")) >= 0;
            case "稳健持有" -> "稳健持有".equals(item.advice());
            default -> true;
        };
    }

    @Override
    public List<String> codes(String groupName) {
        QueryWrapper<WatchStock> wrapper = new QueryWrapper<WatchStock>()
                .select("stock_code")
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .orderByAsc("priority")
                .orderByDesc("created_at");
        if (groupName != null && !groupName.isBlank() && !"全部".equals(groupName)) {
            wrapper.eq("group_name", groupName);
        }
        return watchStockMapper.selectList(wrapper).stream()
                .map(entity -> entity.stockCode)
                .toList();
    }

    private WatchStockResponse buildLightResponse(WatchStock entity, StockQuoteResponse quote) {
        try {
            if (quote == null) {
                return fallbackResponse(entity, "行情暂不可用");
            }
            return buildResponse(entity, quote, EMPTY_FINANCE);
        } catch (RuntimeException ex) {
            log.warn("build watch stock response failed, stockCode={}", entity.stockCode, ex);
            return fallbackResponse(entity, "行情暂不可用");
        }
    }

    @Override
    @Transactional
    public WatchStockResponse add(AddWatchStockRequest request) {
        Long userId = AuthContext.currentUserIdOrDefault();
        String code = request.code().trim();
        String groupName = request.groupName() == null || request.groupName().isBlank() ? "全部" : request.groupName();
        WatchStock existing = watchStockMapper.selectAnyByUserIdAndCode(userId, code);
        if (existing != null && existing.deleted != null && existing.deleted == 0) {
            return buildLightResponse(existing, marketDataService.quote(existing.stockCode));
        }

        StockQuoteResponse quote = marketDataService.quote(code);
        if (existing != null) {
            existing.stockName = quote.name();
            existing.market = quote.market();
            existing.groupName = groupName;
            existing.priority = resolveTopPriority(userId);
            existing.deleted = 0;
            existing.updatedAt = LocalDateTime.now();
            watchStockMapper.restore(existing);
            return buildResponse(existing, quote, EMPTY_FINANCE);
        }

        WatchStock entity = new WatchStock();
        entity.userId = userId;
        entity.stockCode = code;
        entity.stockName = quote.name();
        entity.market = quote.market();
        entity.groupName = groupName;
        entity.priority = resolveTopPriority(userId);
        entity.deleted = 0;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = entity.createdAt;
        try {
            watchStockMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            WatchStock concurrentExisting = watchStockMapper.selectAnyByUserIdAndCode(userId, code);
            if (concurrentExisting != null) {
                return buildLightResponse(concurrentExisting, marketDataService.quote(concurrentExisting.stockCode));
            }
            throw ex;
        }
        return buildResponse(entity, quote, EMPTY_FINANCE);
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

    private int resolveTopPriority(Long userId) {
        Integer minPriority = watchStockMapper.selectMinPriorityByUserId(userId);
        if (minPriority == null) {
            return 10;
        }
        return minPriority - 10;
    }

    private WatchStockResponse fallbackResponse(WatchStock entity, String advice) {
        StockQuoteResponse quote = new StockQuoteResponse(
                entity.stockCode,
                entity.stockName == null ? entity.stockCode : entity.stockName,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                entity.market,
                "LOCAL_FALLBACK",
                LocalDateTime.now()
        );
        return buildResponse(entity, quote, EMPTY_FINANCE, advice, 0);
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
}
