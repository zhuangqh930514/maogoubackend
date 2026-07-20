package com.maogou.stock.dto.ai;

import java.util.List;

/** Result of a best-effort watchlist analysis run. */
public record WatchlistAnalysisResult(
        int requestedCount,
        int analyzedCount,
        List<SkippedStock> skippedStocks
) {
    public WatchlistAnalysisResult {
        skippedStocks = skippedStocks == null ? List.of() : List.copyOf(skippedStocks);
    }

    public int skippedCount() {
        return skippedStocks.size();
    }

    public record SkippedStock(
            String stockCode,
            String stockName,
            String reason
    ) {
    }
}
