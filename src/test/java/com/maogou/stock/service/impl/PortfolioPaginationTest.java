package com.maogou.stock.service.impl;

import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.dto.portfolio.TradePositionAggregate;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.MarketDataService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioPaginationTest {

    @Test
    void aggregatesInSqlButReturnsOnlyTheRequestedPositionPage() {
        TradeRecordMapper mapper = mock(TradeRecordMapper.class);
        MarketDataService market = mock(MarketDataService.class);
        List<TradePositionAggregate> aggregates = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(this::aggregate)
                .toList();
        when(mapper.selectActivePositions(5L)).thenReturn(aggregates);
        when(market.quotesFast(any())).thenReturn(aggregates.stream().collect(java.util.stream.Collectors.toMap(
                item -> item.stockCode,
                item -> new StockQuoteResponse(item.stockCode, item.stockName, new BigDecimal("12"),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "SH", "TEST", LocalDateTime.now()))));

        var response = AuthContext.callAs(5L,
                () -> new PortfolioServiceImpl(mapper, market).portfolio(2, 20));

        assertThat(response.positionTotal()).isEqualTo(21);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.positions()).hasSize(1);
        assertThat(response.positions().get(0).code()).isEqualTo("000021");
        verify(mapper).selectActivePositions(5L);
    }

    private TradePositionAggregate aggregate(int index) {
        TradePositionAggregate value = new TradePositionAggregate();
        value.stockCode = String.format("%06d", index);
        value.stockName = "股票" + index;
        value.totalCost = new BigDecimal("1000");
        value.quantity = 100;
        value.lastTradedAt = LocalDateTime.now().minusMinutes(index);
        return value;
    }
}
