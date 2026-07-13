package com.maogou.stock.service;

import com.maogou.stock.dto.market.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface MarketDataService {
    List<StockSearchResponse> searchStocks(String keyword, int limit);

    List<NewsFlashResponse> latestNews(int limit);

    List<MarketIndexResponse> coreIndexes();

    MarketBreadthResponse marketBreadth();

    SectorHeatmapResponse sectorHeatmap();

    SectorHotStocksResponse marketHotStocks(int limit);

    SectorHotStocksResponse sectorHotStocks(String sectorCode, int limit);

    List<IntradayPointResponse> intraday(String symbol);

    List<KlinePointResponse> kline(String symbol, String period, int limit);

    KlineSeriesSnapshot klineAt(String symbol, String period, int limit, LocalDateTime asOfTime);

    StockDetailResponse stockDetail(String code);

    StockDetailResponse stockDetailForAnalysis(String code);

    default StockDetailResponse stockDetailAt(String code, LocalDateTime asOfTime) {
        throw new UnsupportedOperationException("暂未实现时点化个股详情");
    }

    StockQuoteResponse quote(String code);

    Map<String, StockQuoteResponse> quotes(List<String> codes);

    FinanceSnapshotResponse finance(String code);

    FinanceSnapshotResponse financeAt(String code, LocalDateTime asOfTime);

    List<NewsFlashResponse> latestNewsForAnalysis(int limit);

    default List<NewsFlashResponse> latestNewsForAnalysisAt(int limit, LocalDateTime asOfTime) {
        return latestNewsForAnalysis(limit);
    }
}
