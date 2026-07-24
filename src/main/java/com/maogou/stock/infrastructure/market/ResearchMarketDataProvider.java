package com.maogou.stock.infrastructure.market;

import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.NewsFlashResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface ResearchMarketDataProvider {

    String ENDPOINT_KLINE = "KLINE";
    String ENDPOINT_INDUSTRY_KLINE = "INDUSTRY_KLINE";
    String ENDPOINT_INDUSTRY = "INDUSTRY_MEMBERSHIP";
    String ENDPOINT_NEWS = "NEWS";

    String providerCode();

    default boolean supports(String endpointType) {
        return ENDPOINT_KLINE.equals(endpointType);
    }

    default boolean supports(String endpointType, String symbol) {
        return supports(endpointType);
    }

    KlineSeriesSnapshot fetchKlineAt(String symbol, String period, int limit, LocalDateTime asOfTime);

    default IndustryMembershipData fetchIndustryAt(String stockCode, LocalDateTime asOfTime) {
        throw new UnsupportedOperationException(providerCode() + " 不支持行业归属查询");
    }

    default List<NewsFlashResponse> fetchNewsAt(int limit, LocalDateTime asOfTime) {
        throw new UnsupportedOperationException(providerCode() + " 不支持资讯查询");
    }

    record IndustryMembershipData(
            String stockCode,
            String industryCode,
            String industryName,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDateTime fetchedAt,
            String sourceFingerprint
    ) {
    }
}
