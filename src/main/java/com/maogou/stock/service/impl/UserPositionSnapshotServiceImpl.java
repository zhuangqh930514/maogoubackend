package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.UserPositionSnapshot;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.dto.portfolio.PositionSnapshotSummary;
import com.maogou.stock.dto.portfolio.TradePositionAggregate;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.UserPositionSnapshotMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.UserPositionSnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserPositionSnapshotServiceImpl implements UserPositionSnapshotService {

    private final TradeRecordMapper tradeRecordMapper;
    private final UserPositionSnapshotMapper snapshotMapper;

    public UserPositionSnapshotServiceImpl(
            TradeRecordMapper tradeRecordMapper,
            UserPositionSnapshotMapper snapshotMapper
    ) {
        this.tradeRecordMapper = tradeRecordMapper;
        this.snapshotMapper = snapshotMapper;
    }

    @Override
    @Transactional
    public void rebuild(Long userId, String stockCode, StockQuoteResponse quote) {
        if (userId == null || stockCode == null || stockCode.isBlank()) {
            return;
        }
        TradePositionAggregate position = tradeRecordMapper.selectActivePosition(userId, stockCode.trim());
        if (position == null || position.quantity == null || position.quantity <= 0) {
            snapshotMapper.deleteByUserAndStock(userId, stockCode.trim());
            return;
        }

        UserPositionSnapshot previous = snapshotMapper.selectByUserAndStock(userId, stockCode.trim());
        int quantity = position.quantity;
        BigDecimal totalCost = nonNegative(position.totalCost);
        BigDecimal averageCost = totalCost.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
        UserPositionSnapshot snapshot = new UserPositionSnapshot();
        snapshot.id = previous == null ? null : previous.id;
        snapshot.userId = userId;
        snapshot.stockCode = stockCode.trim();
        snapshot.stockName = firstName(position.stockName, previous == null ? null : previous.stockName, stockCode);
        snapshot.quantity = quantity;
        snapshot.averageCost = averageCost;
        snapshot.totalCost = totalCost;
        snapshot.realizedPnl = previous == null ? BigDecimal.ZERO : previous.realizedPnl;
        snapshot.updatedAt = LocalDateTime.now();

        if (quote == null && previous != null && previous.currentPrice != null) {
            copyQuote(previous, snapshot);
        } else if (quote != null && quote.hasUsablePrice()) {
            snapshot.currentPrice = quote.price();
            snapshot.marketValue = quote.price().multiply(BigDecimal.valueOf(quantity));
            snapshot.unrealizedPnl = snapshot.marketValue.subtract(totalCost);
            snapshot.todayPnl = quote.change() == null ? null : quote.change().multiply(BigDecimal.valueOf(quantity));
            snapshot.todayPnlRate = rate(snapshot.todayPnl,
                    snapshot.marketValue == null || snapshot.todayPnl == null
                            ? null : snapshot.marketValue.subtract(snapshot.todayPnl));
            snapshot.quoteStatus = quote.sourceStatus();
            snapshot.quoteSource = quote.source();
            snapshot.quoteAsOf = quote.sourceAsOf();
            snapshot.calculationStatus = "STALE".equalsIgnoreCase(quote.sourceStatus()) ? "STALE" : "AVAILABLE";
            snapshot.unavailableReason = "STALE".equalsIgnoreCase(quote.sourceStatus())
                    ? quote.message() : null;
        } else {
            snapshot.quoteStatus = quote == null ? "UNAVAILABLE" : quote.sourceStatus();
            snapshot.quoteSource = quote == null ? null : quote.source();
            snapshot.quoteAsOf = quote == null ? null : quote.sourceAsOf();
            snapshot.calculationStatus = "UNAVAILABLE";
            snapshot.unavailableReason = quote == null ? "暂无可用真实行情" : quote.message();
        }
        snapshotMapper.upsert(snapshot);
    }

    @Override
    public void refreshForQuote(StockQuoteResponse quote) {
        if (quote == null || quote.code() == null || !quote.hasUsablePrice()) {
            return;
        }
        Long userId = null;
        // The quote refresh is shared across users. Rebuild only users that really hold it;
        // the mapper query keeps this background path independent from HTTP authentication.
        for (Long holder : tradeRecordMapper.selectUserIdsHolding(quote.code())) {
            rebuild(holder, quote.code(), quote);
        }
    }

    @Override
    public Page page(Long userId, int page, int pageSize) {
        int normalizedSize = Math.max(1, Math.min(pageSize, 100));
        long total = snapshotMapper.countByUserId(userId);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / normalizedSize);
        int normalizedPage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        PositionSnapshotSummary summary = snapshotMapper.selectSummary(userId);
        if (summary == null) {
            summary = new PositionSnapshotSummary();
        }
        List<UserPositionSnapshot> items = total == 0 ? List.of()
                : snapshotMapper.selectPage(userId, normalizedSize, (long) (normalizedPage - 1) * normalizedSize);
        return new Page(items == null ? List.of() : List.copyOf(items), total, summary,
                normalizedPage, normalizedSize, totalPages);
    }

    private static void copyQuote(UserPositionSnapshot previous, UserPositionSnapshot target) {
        target.currentPrice = previous.currentPrice;
        target.marketValue = previous.currentPrice.multiply(BigDecimal.valueOf(target.quantity));
        target.unrealizedPnl = target.marketValue.subtract(target.totalCost);
        target.todayPnl = previous.todayPnl;
        target.todayPnlRate = rate(target.todayPnl,
                target.marketValue == null || target.todayPnl == null ? null : target.marketValue.subtract(target.todayPnl));
        target.quoteStatus = previous.quoteStatus;
        target.quoteSource = previous.quoteSource;
        target.quoteAsOf = previous.quoteAsOf;
        target.calculationStatus = previous.calculationStatus;
        target.unavailableReason = previous.unavailableReason;
    }

    private static BigDecimal rate(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() == 0) {
            return null;
        }
        return value.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private static String firstName(String first, String second, String fallback) {
        return first == null || first.isBlank() ? second == null || second.isBlank() ? fallback : second : first;
    }
}
