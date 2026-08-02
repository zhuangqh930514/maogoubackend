package com.maogou.stock.infrastructure.market;

import com.maogou.stock.dto.market.KlineSeriesSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface HistoricalMarketDataProvider {

    String ENDPOINT_TRADING_CALENDAR = "TRADING_CALENDAR";
    String ENDPOINT_SECURITY_STATE = "SECURITY_STATE";
    String ENDPOINT_HISTORICAL_UNIVERSE = "HISTORICAL_UNIVERSE";
    String ENDPOINT_INDUSTRY_MEMBERSHIP = "INDUSTRY_MEMBERSHIP";
    String ENDPOINT_INDUSTRY_DAILY_BAR = "INDUSTRY_DAILY_BAR";
    String ENDPOINT_BENCHMARK_DAILY_BAR = "BENCHMARK_DAILY_BAR";
    String ENDPOINT_DAILY_BAR_NONE = "DAILY_BAR_NONE";
    String ENDPOINT_DAILY_BAR_QFQ = "DAILY_BAR_QFQ";
    String ENDPOINT_ADJUSTMENT_FACTOR = "ADJUSTMENT_FACTOR";

    String providerCode();

    UniverseCatalog fetchCurrentListedUniverse(int limit, LocalDateTime requestedAt);

    /**
     * Returns the security universe that was valid at one historical date.
     *
     * <p>The old current-directory method remains available for live baseline
     * construction, but it is never a valid substitute for this method during
     * historical replay. Providers that do not have point-in-time membership
     * evidence must fail explicitly instead of returning today's directory.</p>
     */
    default HistoricalUniverse fetchHistoricalUniverse(
            int limit,
            LocalDate tradeDate,
            LocalDateTime asOfTime
    ) {
        throw new HistoricalDataUnavailableException(
                providerCode(), ENDPOINT_HISTORICAL_UNIVERSE,
                "provider 未提供按历史日期固化的证券主数据/研究池证据");
    }

    /**
     * Declares the data contracts this provider can actually fulfill. The
     * declaration is descriptive only; the preflight still performs real
     * requests and validates every response before allowing a run.
     */
    default Set<String> historicalCapabilities() {
        return Set.of(ENDPOINT_DAILY_BAR_NONE, ENDPOINT_DAILY_BAR_QFQ);
    }

    KlineSeriesSnapshot fetchHistoricalKline(
            String symbol,
            int limit,
            LocalDateTime asOfTime,
            String adjustmentMode
    );

    /**
     * A provider must identify itself as a real source.  The historical
     * bootstrap pipeline rejects demo/local fixtures before they can create
     * formal evidence, even when a fixture happens to implement this interface.
     */
    default boolean syntheticSource() {
        String code = providerCode() == null ? "" : providerCode().trim().toUpperCase();
        return code.contains("MOCK") || code.contains("FIXTURE") || code.contains("LOCAL");
    }

    class HistoricalDataUnavailableException extends IllegalStateException {
        private final String providerCode;
        private final String endpointType;

        public HistoricalDataUnavailableException(
                String providerCode,
                String endpointType,
                String message
        ) {
            super(message);
            this.providerCode = providerCode;
            this.endpointType = endpointType;
        }

        public String providerCode() {
            return providerCode;
        }

        public String endpointType() {
            return endpointType;
        }
    }

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

    record HistoricalUniverse(
            String providerCode,
            LocalDate tradeDate,
            LocalDateTime asOfTime,
            LocalDateTime observedAt,
            String sourceUri,
            String sourceRevision,
            String sourceFingerprint,
            List<Security> securities
    ) {
        public HistoricalUniverse {
            securities = securities == null ? List.of() : List.copyOf(securities);
        }
    }
}
