package com.maogou.stock.infrastructure.market;

import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.NewsFlashResponse;

import java.time.LocalDateTime;
import java.util.List;

public interface ResearchMarketDataClient {

    ResearchSourceResult<KlineSeriesSnapshot> fetchKlineAt(
            String symbol,
            String period,
            int limit,
            LocalDateTime asOfTime
    );

    ResearchSourceResult<ResearchMarketDataProvider.IndustryMembershipData> fetchIndustryAt(
            String stockCode,
            LocalDateTime asOfTime
    );

    ResearchSourceResult<List<NewsFlashResponse>> fetchNewsAt(int limit, LocalDateTime asOfTime);
}
