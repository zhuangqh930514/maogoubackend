package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.UserPositionSnapshot;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.dto.portfolio.PortfolioSummaryResponse;
import com.maogou.stock.dto.portfolio.PositionResponse;
import com.maogou.stock.dto.portfolio.TradeRecordCreateRequest;
import com.maogou.stock.dto.portfolio.TradeRecordResponse;
import com.maogou.stock.dto.portfolio.TradePositionAggregate;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.PortfolioService;
import com.maogou.stock.service.UserPositionSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class PortfolioServiceImpl implements PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioServiceImpl.class);

    private final TradeRecordMapper tradeRecordMapper;
    private final MarketDataService marketDataService;
    private final UserPositionSnapshotService positionSnapshotService;

    public PortfolioServiceImpl(TradeRecordMapper tradeRecordMapper, MarketDataService marketDataService) {
        this(tradeRecordMapper, marketDataService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PortfolioServiceImpl(
            TradeRecordMapper tradeRecordMapper,
            MarketDataService marketDataService,
            UserPositionSnapshotService positionSnapshotService
    ) {
        this.tradeRecordMapper = tradeRecordMapper;
        this.marketDataService = marketDataService;
        this.positionSnapshotService = positionSnapshotService;
    }

    @Override
    public List<TradeRecordResponse> trades() {
        return tradeRecordMapper.selectList(baseTradeQuery()).stream()
                .map(this::toTradeResponse)
                .toList();
    }

    @Override
    @Transactional
    public TradeRecordResponse addBuyRecord(TradeRecordCreateRequest request) {
        StockQuoteResponse quote = marketDataService.quote(request.code());
        TradeRecord entity = new TradeRecord();
        entity.userId = AuthContext.currentUserIdOrDefault();
        entity.stockCode = request.code();
        entity.stockName = quote.name();
        entity.side = TradeSide.BUY;
        entity.price = request.buyPrice();
        entity.quantity = request.quantity();
        entity.fee = BigDecimal.ZERO;
        entity.tradedAt = request.buyTime();
        entity.deleted = 0;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = entity.createdAt;
        tradeRecordMapper.insert(entity);
        if (positionSnapshotService != null) {
            positionSnapshotService.rebuild(entity.userId, entity.stockCode, quote);
        }
        return toTradeResponse(entity);
    }

    @Override
    @Transactional
    public void removePositions(List<String> codes) {
        List<String> normalizedCodes = codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedCodes.isEmpty()) {
            return;
        }
        tradeRecordMapper.update(null, new UpdateWrapper<TradeRecord>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .in("stock_code", normalizedCodes)
                .set("deleted", 1)
                .set("updated_at", LocalDateTime.now()));
        if (positionSnapshotService != null) {
            Long userId = AuthContext.currentUserIdOrDefault();
            normalizedCodes.forEach(code -> positionSnapshotService.rebuild(userId, code, null));
        }
    }

    @Override
    public PortfolioSummaryResponse portfolio(int page, int pageSize) {
        if (positionSnapshotService != null) {
            return portfolioFromSnapshot(positionSnapshotService.page(
                    AuthContext.currentUserIdOrDefault(), page, pageSize));
        }
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 100));
        List<TradePositionAggregate> activePositions = tradeRecordMapper.selectActivePositions(
                AuthContext.currentUserIdOrDefault());
        Map<String, StockQuoteResponse> quotes = marketDataService.quotesFast(activePositions.stream()
                .map(position -> position.stockCode)
                .toList());
        List<PositionResponse> allPositions = activePositions.stream()
                .map(position -> toPositionResponse(position, quotes.get(position.stockCode)))
                .toList();
        BigDecimal totalCost = allPositions.stream().map(PositionResponse::cost)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        long unpricedPositionCount = allPositions.stream()
                .filter(item -> !"AVAILABLE".equalsIgnoreCase(item.calculationStatus())).count();
        long pricedPositionCount = allPositions.size() - unpricedPositionCount;
        boolean summaryComplete = unpricedPositionCount == 0;
        BigDecimal pricedMarketValue = allPositions.stream().map(PositionResponse::marketValue)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalMarketValue = summaryComplete ? pricedMarketValue : null;
        BigDecimal totalProfit = summaryComplete ? totalMarketValue.subtract(totalCost) : null;
        BigDecimal profitRate = !summaryComplete || totalCost.signum() == 0 ? null : totalProfit
                .multiply(new BigDecimal("100"))
                .divide(totalCost, 2, RoundingMode.HALF_UP);
        BigDecimal todayProfit = summaryComplete ? allPositions.stream().map(PositionResponse::todayProfit)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal todayBase = summaryComplete ? totalMarketValue.subtract(todayProfit) : null;
        BigDecimal todayProfitRate = !summaryComplete || todayBase.signum() == 0 ? null : todayProfit
                .multiply(new BigDecimal("100"))
                .divide(todayBase, 2, RoundingMode.HALF_UP);
        int totalPages = allPositions.isEmpty() ? 0 : (int) Math.ceil((double) allPositions.size() / normalizedPageSize);
        int normalizedPage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        int fromIndex = Math.min((normalizedPage - 1) * normalizedPageSize, allPositions.size());
        int toIndex = Math.min(fromIndex + normalizedPageSize, allPositions.size());
        List<PositionResponse> positions = allPositions.subList(fromIndex, toIndex);
        return new PortfolioSummaryResponse(
                totalCost, totalMarketValue, totalProfit, profitRate, todayProfit, todayProfitRate,
                positions, allPositions.size(), normalizedPage, normalizedPageSize, totalPages,
                pricedPositionCount, unpricedPositionCount, summaryComplete);
    }

    private PortfolioSummaryResponse portfolioFromSnapshot(UserPositionSnapshotService.Page result) {
        var summary = result.summary();
        long total = result.total();
        long priced = summary.pricedPositionCount;
        long unpriced = summary.unpricedPositionCount;
        boolean complete = unpriced == 0;
        BigDecimal totalCost = zero(summary.totalCost);
        BigDecimal totalMarketValue = complete ? zero(summary.totalMarketValue) : null;
        BigDecimal totalProfit = complete ? zero(summary.totalUnrealizedPnl) : null;
        BigDecimal profitRate = !complete || totalCost.signum() == 0 ? null
                : totalProfit.multiply(BigDecimal.valueOf(100)).divide(totalCost, 2, RoundingMode.HALF_UP);
        BigDecimal todayProfit = complete ? zero(summary.totalTodayPnl) : null;
        BigDecimal todayBase = complete && totalMarketValue != null && todayProfit != null
                ? totalMarketValue.subtract(todayProfit) : null;
        BigDecimal todayProfitRate = todayBase == null || todayBase.signum() == 0 ? null
                : todayProfit.multiply(BigDecimal.valueOf(100)).divide(todayBase, 2, RoundingMode.HALF_UP);
        List<PositionResponse> positions = result.items().stream().map(this::toPositionResponse).toList();
        return new PortfolioSummaryResponse(totalCost, totalMarketValue, totalProfit, profitRate,
                todayProfit, todayProfitRate, positions, total, result.page(), result.pageSize(), result.totalPages(),
                priced, unpriced, complete);
    }

    private PositionResponse toPositionResponse(UserPositionSnapshot position) {
        return new PositionResponse(position.stockCode, position.stockName, position.averageCost,
                position.quantity, position.currentPrice, position.totalCost, position.marketValue,
                position.unrealizedPnl, rate(position.unrealizedPnl, position.totalCost), position.todayPnl,
                position.todayPnlRate, position.quoteStatus, position.quoteSource, position.quoteAsOf,
                position.calculationStatus, position.unavailableReason);
    }

    private static BigDecimal rate(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() == 0) {
            return null;
        }
        return value.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private QueryWrapper<TradeRecord> baseTradeQuery() {
        return new QueryWrapper<TradeRecord>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .orderByDesc("traded_at");
    }

    private TradeRecordResponse toTradeResponse(TradeRecord entity) {
        return new TradeRecordResponse(
                entity.id,
                entity.stockCode,
                entity.stockName,
                entity.price,
                entity.quantity,
                entity.tradedAt
        );
    }

    private PositionResponse toPositionResponse(TradePositionAggregate position, StockQuoteResponse quote) {
        int quantity = position.quantity == null ? 0 : position.quantity;
        BigDecimal totalCost = position.totalCost == null ? BigDecimal.ZERO : position.totalCost;
        BigDecimal buyPrice = quantity == 0 ? BigDecimal.ZERO
                : totalCost.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
        BigDecimal cost = buyPrice.multiply(BigDecimal.valueOf(quantity));
        if (quote == null || !quote.hasUsablePrice()) {
            return new PositionResponse(
                    position.stockCode,
                    position.stockName,
                    buyPrice,
                    quantity,
                    null,
                    cost,
                    null,
                    null,
                    null,
                    null,
                    null,
                    quote == null ? "UNAVAILABLE" : quote.sourceStatus(),
                    quote == null ? null : quote.source(),
                    quote == null ? null : quote.sourceAsOf(),
                    "UNAVAILABLE",
                    quote == null ? "行情暂不可用" : quote.message());
        }
        BigDecimal marketValue = quote.price().multiply(BigDecimal.valueOf(quantity));
        BigDecimal profit = marketValue.subtract(cost);
        BigDecimal profitRate = cost.signum() == 0 ? BigDecimal.ZERO : profit
                .multiply(new BigDecimal("100"))
                .divide(cost, 2, RoundingMode.HALF_UP);
        BigDecimal todayProfit = quote.change() == null ? null : quote.change().multiply(BigDecimal.valueOf(quantity));
        return new PositionResponse(
                position.stockCode,
                position.stockName,
                buyPrice,
                quantity,
                quote.price(),
                cost,
                marketValue,
                profit,
                profitRate,
                todayProfit,
                quote.percent(),
                quote.sourceStatus(),
                quote.source(),
                quote.sourceAsOf(),
                "AVAILABLE",
                null
        );
    }

}
