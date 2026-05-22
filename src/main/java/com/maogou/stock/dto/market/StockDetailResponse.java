package com.maogou.stock.dto.market;

import java.util.List;

public record StockDetailResponse(
        StockQuoteResponse quote,
        FinanceSnapshotResponse finance,
        List<IntradayPointResponse> intraday,
        List<KlinePointResponse> kline,
        String aiAdvice,
        Integer aiScore
) {
}
