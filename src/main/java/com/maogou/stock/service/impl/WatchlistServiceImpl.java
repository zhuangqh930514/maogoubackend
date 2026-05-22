package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.dto.watchlist.AddWatchStockRequest;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.WatchlistService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WatchlistServiceImpl implements WatchlistService {

    public static final long DEFAULT_USER_ID = 1L;

    private final WatchStockMapper watchStockMapper;
    private final MarketDataService marketDataService;

    public WatchlistServiceImpl(WatchStockMapper watchStockMapper, MarketDataService marketDataService) {
        this.watchStockMapper = watchStockMapper;
        this.marketDataService = marketDataService;
    }

    @Override
    public List<WatchStockResponse> list(String groupName) {
        QueryWrapper<WatchStock> wrapper = new QueryWrapper<WatchStock>()
                .eq("user_id", DEFAULT_USER_ID)
                .orderByAsc("priority")
                .orderByDesc("created_at");
        if (groupName != null && !groupName.isBlank() && !"全部".equals(groupName)) {
            wrapper.eq("group_name", groupName);
        }
        return watchStockMapper.selectList(wrapper).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public WatchStockResponse add(AddWatchStockRequest request) {
        StockQuoteResponse quote = marketDataService.quote(request.code());
        WatchStock entity = new WatchStock();
        entity.userId = DEFAULT_USER_ID;
        entity.stockCode = request.code();
        entity.stockName = quote.name();
        entity.market = quote.market();
        entity.groupName = request.groupName() == null || request.groupName().isBlank() ? "全部" : request.groupName();
        entity.priority = 100;
        entity.deleted = 0;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = entity.createdAt;
        watchStockMapper.insert(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public void remove(String code) {
        watchStockMapper.delete(new QueryWrapper<WatchStock>()
                .eq("user_id", DEFAULT_USER_ID)
                .eq("stock_code", code));
    }

    private WatchStockResponse toResponse(WatchStock entity) {
        StockQuoteResponse quote = marketDataService.quote(entity.stockCode);
        FinanceSnapshotResponse finance = marketDataService.finance(entity.stockCode);
        int aiScore = quote.percent().signum() < 0 ? 64 : quote.percent().doubleValue() >= 3 ? 86 : 72;
        String advice = quote.percent().signum() < 0 ? "控制仓位" : quote.percent().doubleValue() >= 3 ? "突破跟踪" : "稳健持有";
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
}
