package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistPaginationTest {

    @Test
    void queriesAndQuotesOnlyTheRequestedDefaultPage() {
        WatchStockMapper mapper = mock(WatchStockMapper.class);
        MarketDataService market = mock(MarketDataService.class);
        when(mapper.selectCount(any())).thenReturn(120L);
        when(mapper.selectList(any())).thenReturn(List.of(stock("600519"), stock("000001")));
        when(market.quotesFast(any())).thenReturn(Map.of(
                "600519", quote("600519", new BigDecimal("2.1")),
                "000001", quote("000001", new BigDecimal("-0.5"))));

        var response = AuthContext.callAs(5L,
                () -> new WatchlistServiceImpl(mapper, market).page("全部", 2, 50));

        assertThat(response.total()).isEqualTo(120);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.items()).extracting("code").containsExactly("600519", "000001");
        ArgumentCaptor<List<String>> codes = ArgumentCaptor.forClass(List.class);
        verify(market).quotesFast(codes.capture());
        assertThat(codes.getValue()).containsExactly("600519", "000001");
        ArgumentCaptor<Wrapper<WatchStock>> query = wrapperCaptor();
        verify(mapper).selectList(query.capture());
        assertThat(query.getValue().getCustomSqlSegment()).contains("LIMIT 50 OFFSET 50");
    }

    private static WatchStock stock(String code) {
        WatchStock value = new WatchStock();
        value.id = Long.parseLong(code);
        value.stockCode = code;
        value.stockName = code;
        value.priority = 10;
        return value;
    }

    private static StockQuoteResponse quote(String code, BigDecimal percent) {
        return new StockQuoteResponse(code, code, BigDecimal.TEN, BigDecimal.ONE, percent,
                BigDecimal.ONE, "SH", "TEST", LocalDateTime.now());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<WatchStock>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }
}
