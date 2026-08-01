package com.maogou.stock.service;

import com.maogou.stock.dto.market.StockQuoteResponse;

public interface MarketSnapshotService {
    void recordRealtimeQuote(StockQuoteResponse quote);
}
