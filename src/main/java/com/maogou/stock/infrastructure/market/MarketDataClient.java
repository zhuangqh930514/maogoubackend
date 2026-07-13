package com.maogou.stock.infrastructure.market;

import com.maogou.stock.dto.market.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface MarketDataClient {
    List<StockSearchResponse> searchStocks(String keyword, int limit);

    List<NewsFlashResponse> fetchLatestNews(int limit);

    List<MarketIndexResponse> fetchCoreIndexes();

    MarketBreadthResponse fetchMarketBreadth();

    SectorHeatmapResponse fetchSectorHeatmap();

    List<SectorHotStockResponse> fetchMarketHotStocks(int limit);

    List<SectorHotStockResponse> fetchSectorHotStocks(String sectorCode, int limit);

    List<IntradayPointResponse> fetchIntraday(String symbol);

    List<KlinePointResponse> fetchKline(String symbol, String period, int limit);

    default KlineSeriesSnapshot fetchKlineAt(
            String symbol,
            String period,
            int limit,
            LocalDateTime asOfTime
    ) {
        return new KlineSeriesSnapshot(
                symbol, period, "UNAVAILABLE", "UNAVAILABLE", asOfTime, LocalDateTime.now(), "", List.of());
    }

    StockQuoteResponse fetchQuote(String stockCode);

    default Map<String, StockQuoteResponse> fetchQuotes(List<String> stockCodes) {
        Map<String, StockQuoteResponse> quotes = new LinkedHashMap<>();
        if (stockCodes == null) {
            return quotes;
        }
        for (String stockCode : stockCodes) {
            StockQuoteResponse quote = fetchQuote(stockCode);
            quotes.put(stockCode, quote);
        }
        return quotes;
    }

    FinanceSnapshotResponse fetchFinance(String stockCode);

    default FinanceSnapshotResponse fetchFinanceAt(String stockCode, LocalDateTime asOfTime) {
        return FinanceSnapshotResponse.empty();
    }
}
