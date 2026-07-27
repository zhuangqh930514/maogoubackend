package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.dto.watchlist.AddWatchStockRequest;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.dto.watchlist.WatchlistQuery;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
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
                .eq("deleted", 0)
                .orderByDesc("pinned")
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
        return page(new WatchlistQuery(view, null, "MANUAL", false, page, pageSize));
    }

    @Override
    public PageResponse<WatchStockResponse> page(WatchlistQuery request) {
        WatchlistQuery query = request == null
                ? new WatchlistQuery("全部", null, "MANUAL", false, 1, 50) : request;
        int normalizedPageSize = Math.max(1, Math.min(query.pageSize(), 100));
        String normalizedView = query.view() == null || query.view().isBlank() ? "全部" : query.view().trim();
        String normalizedSort = normalizeSort(query.sort());
        if ("全部".equals(normalizedView) && "MANUAL".equals(normalizedSort)) {
            return pageManual(query.keyword(), query.pinnedOnly(), normalizedPageSize, query.page());
        }

        List<WatchStock> stocks = watchStockMapper.selectList(baseQuery(query.keyword(), query.pinnedOnly())
                .orderByDesc("pinned").orderByAsc("priority").orderByDesc("created_at"));
        Map<String, StockQuoteResponse> quotes = marketDataService.quotesFast(stocks.stream()
                .map(entity -> entity.stockCode).toList());
        List<WatchStockResponse> filtered = stocks.stream()
                .map(entity -> buildLightResponse(entity, quotes.get(entity.stockCode)))
                .filter(item -> matchesView(item, normalizedView))
                .sorted(responseComparator(normalizedSort))
                .toList();
        return pageResponses(filtered, normalizedPageSize, query.page());
    }

    private PageResponse<WatchStockResponse> pageManual(String keyword, boolean pinnedOnly, int pageSize, int page) {
        QueryWrapper<WatchStock> countQuery = baseQuery(keyword, pinnedOnly);
        long total = watchStockMapper.selectCount(countQuery);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        int normalizedPage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        if (total == 0) {
            return PageResponse.of(List.of(), 0, normalizedPage, pageSize);
        }

        long offset = (long) (normalizedPage - 1) * pageSize;
        QueryWrapper<WatchStock> pageQuery = baseQuery(keyword, pinnedOnly)
                .orderByDesc("pinned")
                .orderByAsc("priority")
                .orderByDesc("created_at")
                .last("LIMIT " + pageSize + " OFFSET " + offset);
        List<WatchStock> stocks = watchStockMapper.selectList(pageQuery);
        Map<String, StockQuoteResponse> quotes = marketDataService.quotesFast(stocks.stream()
                .map(entity -> entity.stockCode).toList());
        List<WatchStockResponse> items = stocks.stream()
                .map(entity -> buildLightResponse(entity, quotes.get(entity.stockCode)))
                .toList();
        return PageResponse.of(items, total, normalizedPage, pageSize);
    }

    private QueryWrapper<WatchStock> baseQuery(String keyword, boolean pinnedOnly) {
        QueryWrapper<WatchStock> query = new QueryWrapper<WatchStock>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .eq("deleted", 0);
        if (pinnedOnly) {
            query.eq("pinned", 1);
        }
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like("stock_code", value).or().like("stock_name", value));
        }
        return query;
    }

    private static PageResponse<WatchStockResponse> pageResponses(List<WatchStockResponse> items, int pageSize, int page) {
        int totalPages = items.isEmpty() ? 0 : (int) Math.ceil((double) items.size() / pageSize);
        int normalizedPage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        int fromIndex = Math.min((normalizedPage - 1) * pageSize, items.size());
        int toIndex = Math.min(fromIndex + pageSize, items.size());
        return PageResponse.of(items.subList(fromIndex, toIndex), items.size(), normalizedPage, pageSize);
    }

    private static String normalizeSort(String sort) {
        String normalized = sort == null ? "MANUAL" : sort.trim().toUpperCase();
        return List.of("MANUAL", "AI_SCORE_DESC", "PERCENT_DESC", "PERCENT_ASC", "VOLUME_RATIO_DESC")
                .contains(normalized) ? normalized : "MANUAL";
    }

    private static Comparator<WatchStockResponse> responseComparator(String sort) {
        Comparator<WatchStockResponse> pinnedFirst = Comparator.comparing(WatchStockResponse::pinned).reversed();
        return switch (sort) {
            case "AI_SCORE_DESC" -> pinnedFirst.thenComparing(WatchStockResponse::aiScore, Comparator.nullsLast(Comparator.reverseOrder()));
            case "PERCENT_DESC" -> pinnedFirst.thenComparing(WatchStockResponse::percent, Comparator.nullsLast(Comparator.reverseOrder()));
            case "PERCENT_ASC" -> pinnedFirst.thenComparing(WatchStockResponse::percent, Comparator.nullsLast(Comparator.naturalOrder()));
            case "VOLUME_RATIO_DESC" -> pinnedFirst.thenComparing(WatchStockResponse::volumeRatio, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> pinnedFirst;
        };
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
                .eq("deleted", 0)
                .orderByDesc("pinned")
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
        entity.pinned = 0;
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
        List<WatchStock> current = watchStockMapper.selectList(new QueryWrapper<WatchStock>()
                .eq("user_id", userId).eq("deleted", 0)
                .orderByDesc("pinned").orderByAsc("priority").orderByDesc("created_at"));
        Map<String, WatchStock> byCode = current.stream()
                .collect(java.util.stream.Collectors.toMap(item -> item.stockCode, item -> item, (left, right) -> left, LinkedHashMap::new));
        normalizedCodes = normalizedCodes.stream().filter(byCode::containsKey).toList();
        LinkedHashMap<String, WatchStock> ordered = new LinkedHashMap<>();
        normalizedCodes.forEach(code -> ordered.put(code, byCode.get(code)));
        current.forEach(item -> ordered.putIfAbsent(item.stockCode, item));
        LocalDateTime now = LocalDateTime.now();
        int priority = 10;
        for (boolean pinned : List.of(true, false)) {
            for (WatchStock original : ordered.values()) {
                if ((original.pinned != null && original.pinned == 1) != pinned) {
                    continue;
                }
                WatchStock entity = new WatchStock();
                entity.userId = userId;
                entity.stockCode = original.stockCode;
                entity.priority = priority;
                entity.updatedAt = now;
                watchStockMapper.updatePriority(entity);
                priority += 10;
            }
        }
    }

    @Override
    @Transactional
    public void pin(String code, boolean pinned) {
        String normalizedCode = normalizeCodes(List.of(code)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("股票代码不能为空"));
        WatchStock entity = new WatchStock();
        entity.userId = AuthContext.currentUserIdOrDefault();
        entity.stockCode = normalizedCode;
        entity.pinned = pinned ? 1 : 0;
        entity.updatedAt = LocalDateTime.now();
        if (watchStockMapper.updatePinned(entity) == 0) {
            throw new IllegalArgumentException("自选股不存在：" + normalizedCode);
        }
        List<String> codes = watchStockMapper.selectList(new QueryWrapper<WatchStock>()
                        .select("stock_code").eq("user_id", entity.userId).eq("deleted", 0)
                        .orderByDesc("pinned").orderByAsc("priority").orderByDesc("created_at"))
                .stream().map(item -> item.stockCode).toList();
        reorder(codes);
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
                entity.groupName,
                entity.pinned != null && entity.pinned == 1
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
