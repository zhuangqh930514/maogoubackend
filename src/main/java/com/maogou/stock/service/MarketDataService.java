package com.maogou.stock.service;

import com.maogou.stock.dto.market.*;

import java.util.List;

public interface MarketDataService {
    List<StockSearchResponse> searchStocks(String keyword, int limit);

    List<NewsFlashResponse> latestNews(int limit);

    List<MarketIndexResponse> coreIndexes();

    MarketBreadthResponse marketBreadth();

    SectorHeatmapResponse sectorHeatmap();

    List<SectorHotStockResponse> marketHotStocks(int limit);

    List<SectorHotStockResponse> sectorHotStocks(String sectorCode, int limit);

    List<IntradayPointResponse> intraday(String symbol);

    List<KlinePointResponse> kline(String symbol, String period, int limit);

    StockDetailResponse stockDetail(String code);

    StockQuoteResponse quote(String code);

    FinanceSnapshotResponse finance(String code);
}
