package com.maogou.stock.dto.home;

import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.market.MarketBreadthResponse;
import com.maogou.stock.dto.market.MarketIndexResponse;
import com.maogou.stock.dto.market.NewsFlashResponse;
import com.maogou.stock.dto.market.SectorHotStocksResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record HomeOverviewResponse(
        List<NewsFlashResponse> news,
        List<MarketIndexResponse> indexes,
        MarketBreadthResponse breadth,
        SectorHotStocksResponse hotStocks,
        AiAnalysisReportResponse latestAiReport,
        List<String> watchlistCodes,
        Map<String, String> warnings,
        LocalDateTime servedAt
) {
}
