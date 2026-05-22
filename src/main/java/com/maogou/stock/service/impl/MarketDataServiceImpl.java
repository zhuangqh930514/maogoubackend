package com.maogou.stock.service.impl;

import com.maogou.stock.dto.market.*;
import com.maogou.stock.infrastructure.market.MarketDataClient;
import com.maogou.stock.service.MarketDataService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MarketDataServiceImpl implements MarketDataService {

    private final MarketDataClient marketDataClient;

    public MarketDataServiceImpl(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @Override
    public List<NewsFlashResponse> latestNews(int limit) {
        return marketDataClient.fetchLatestNews(limit);
    }

    @Override
    public List<MarketIndexResponse> coreIndexes() {
        return marketDataClient.fetchCoreIndexes();
    }

    @Override
    public List<IntradayPointResponse> intraday(String symbol) {
        return marketDataClient.fetchIntraday(symbol);
    }

    @Override
    public List<KlinePointResponse> kline(String symbol, String period, int limit) {
        return marketDataClient.fetchKline(symbol, period, limit);
    }

    @Override
    public StockDetailResponse stockDetail(String code) {
        StockQuoteResponse quote = quote(code);
        FinanceSnapshotResponse finance = finance(code);
        List<IntradayPointResponse> intraday = intraday(code);
        List<KlinePointResponse> kline = kline(code, "day", 60);
        return new StockDetailResponse(
                quote,
                finance,
                intraday,
                kline,
                adviceByPercent(quote.percent()),
                scoreByPercent(quote.percent())
        );
    }

    @Override
    public StockQuoteResponse quote(String code) {
        return marketDataClient.fetchQuote(code);
    }

    @Override
    public FinanceSnapshotResponse finance(String code) {
        return marketDataClient.fetchFinance(code);
    }

    private static String adviceByPercent(BigDecimal percent) {
        if (percent.compareTo(new BigDecimal("3")) >= 0) {
            return "突破跟踪";
        }
        if (percent.compareTo(BigDecimal.ZERO) < 0) {
            return "控制仓位";
        }
        return "稳健持有";
    }

    private static int scoreByPercent(BigDecimal percent) {
        if (percent.compareTo(new BigDecimal("3")) >= 0) {
            return 86;
        }
        if (percent.compareTo(BigDecimal.ZERO) < 0) {
            return 64;
        }
        return 72;
    }
}
