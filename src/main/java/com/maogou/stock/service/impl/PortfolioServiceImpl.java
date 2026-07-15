package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.maogou.stock.domain.entity.TradeRecord;
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

    public PortfolioServiceImpl(TradeRecordMapper tradeRecordMapper, MarketDataService marketDataService) {
        this.tradeRecordMapper = tradeRecordMapper;
        this.marketDataService = marketDataService;
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
    }

    @Override
    public PortfolioSummaryResponse portfolio(int page, int pageSize) {
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 100));
        List<TradePositionAggregate> activePositions = tradeRecordMapper.selectActivePositions(
                AuthContext.currentUserIdOrDefault());
        Map<String, StockQuoteResponse> quotes = marketDataService.quotesFast(activePositions.stream()
                .map(position -> position.stockCode)
                .toList());
        List<PositionResponse> allPositions = activePositions.stream()
                .map(position -> toPositionResponse(position, quotes.get(position.stockCode)))
                .toList();
        BigDecimal totalCost = allPositions.stream().map(PositionResponse::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalMarketValue = allPositions.stream().map(PositionResponse::marketValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfit = totalMarketValue.subtract(totalCost);
        BigDecimal profitRate = totalCost.signum() == 0 ? BigDecimal.ZERO : totalProfit
                .multiply(new BigDecimal("100"))
                .divide(totalCost, 2, RoundingMode.HALF_UP);
        BigDecimal todayProfit = allPositions.stream().map(PositionResponse::todayProfit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayBase = totalMarketValue.subtract(todayProfit);
        BigDecimal todayProfitRate = todayBase.signum() == 0 ? BigDecimal.ZERO : todayProfit
                .multiply(new BigDecimal("100"))
                .divide(todayBase, 2, RoundingMode.HALF_UP);
        int totalPages = allPositions.isEmpty() ? 0 : (int) Math.ceil((double) allPositions.size() / normalizedPageSize);
        int normalizedPage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        int fromIndex = Math.min((normalizedPage - 1) * normalizedPageSize, allPositions.size());
        int toIndex = Math.min(fromIndex + normalizedPageSize, allPositions.size());
        List<PositionResponse> positions = allPositions.subList(fromIndex, toIndex);
        return new PortfolioSummaryResponse(
                totalCost, totalMarketValue, totalProfit, profitRate, todayProfit, todayProfitRate,
                positions, allPositions.size(), normalizedPage, normalizedPageSize, totalPages);
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
        StockQuoteResponse resolvedQuote = quote == null ? fallbackQuote(position) : quote;
        int quantity = position.quantity == null ? 0 : position.quantity;
        BigDecimal totalCost = position.totalCost == null ? BigDecimal.ZERO : position.totalCost;
        BigDecimal buyPrice = quantity == 0 ? BigDecimal.ZERO
                : totalCost.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
        BigDecimal cost = buyPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal marketValue = resolvedQuote.price().multiply(BigDecimal.valueOf(quantity));
        BigDecimal profit = marketValue.subtract(cost);
        BigDecimal profitRate = cost.signum() == 0 ? BigDecimal.ZERO : profit
                .multiply(new BigDecimal("100"))
                .divide(cost, 2, RoundingMode.HALF_UP);
        BigDecimal todayProfit = resolvedQuote.change().multiply(BigDecimal.valueOf(quantity));
        return new PositionResponse(
                position.stockCode,
                position.stockName,
                buyPrice,
                quantity,
                resolvedQuote.price(),
                cost,
                marketValue,
                profit,
                profitRate,
                todayProfit,
                resolvedQuote.percent()
        );
    }

    private StockQuoteResponse fallbackQuote(TradePositionAggregate position) {
        log.warn("portfolio quote missing, use zero quote fallback, stockCode={}", position.stockCode);
        return new StockQuoteResponse(
                position.stockCode,
                position.stockName == null ? position.stockCode : position.stockName,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "LOCAL_FALLBACK",
                LocalDateTime.now()
        );
    }

}
