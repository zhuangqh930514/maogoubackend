package com.maogou.stock.infrastructure.market;

import com.maogou.stock.dto.market.KlineSeriesSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface HistoricalMarketDataProvider {

    String providerCode();

    UniverseCatalog fetchCurrentListedUniverse(int limit, LocalDateTime requestedAt);

    KlineSeriesSnapshot fetchHistoricalKline(
            String symbol,
            int limit,
            LocalDateTime asOfTime,
            String adjustmentMode
    );

    record Security(
            String stockCode,
            String stockName,
            String market,
            LocalDate listedOn
    ) {
    }

    record UniverseCatalog(
            String providerCode,
            LocalDateTime fetchedAt,
            String sourceUri,
            String sourceFingerprint,
            List<Security> securities
    ) {
        public UniverseCatalog {
            securities = securities == null ? List.of() : List.copyOf(securities);
        }
    }
}
