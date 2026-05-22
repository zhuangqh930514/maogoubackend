package com.maogou.stock.infrastructure.market;

import com.maogou.stock.dto.market.*;

import java.util.List;

public interface MarketDataClient {
    List<NewsFlashResponse> fetchLatestNews(int limit);

    List<MarketIndexResponse> fetchCoreIndexes();

    List<IntradayPointResponse> fetchIntraday(String symbol);

    List<KlinePointResponse> fetchKline(String symbol, String period, int limit);

    StockQuoteResponse fetchQuote(String stockCode);

    FinanceSnapshotResponse fetchFinance(String stockCode);
}
