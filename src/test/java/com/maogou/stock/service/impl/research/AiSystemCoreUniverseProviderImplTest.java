package com.maogou.stock.service.impl.research;

import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSystemCoreUniverseProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSystemCoreUniverseProviderImplTest {

    @Test
    void buildsBalancedBaselineAndFiltersStAndNewStocks() {
        LocalDate tradeDate = LocalDate.of(2026, 7, 17);
        LocalDateTime asOfTime = tradeDate.atTime(16, 0);
        AiSystemCoreUniverseProvider provider = new AiSystemCoreUniverseProviderImpl(List.of(
                new StubProvider("EASTMONEY", catalog(tradeDate, 280, true))
        ));

        List<AiResearchUniverseService.UniverseCandidate> result = provider.baselineCandidates(
                tradeDate, asOfTime, 200);

        assertThat(result).hasSize(240);
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.sourceType()).isEqualTo("SYSTEM_BASELINE");
            assertThat(candidate.included()).isTrue();
            assertThat(candidate.stockName()).doesNotContain("ST");
        });
        assertThat(result).anySatisfy(candidate -> assertThat(candidate.stockCode()).startsWith("600"));
        assertThat(result).anySatisfy(candidate -> assertThat(candidate.stockCode()).startsWith("000"));
        assertThat(result).anySatisfy(candidate -> assertThat(candidate.stockCode()).startsWith("300"));
        assertThat(result).anySatisfy(candidate -> assertThat(candidate.stockCode()).startsWith("688"));
    }

    @Test
    void fallsBackToNextProviderWhenPreferredProviderFails() {
        LocalDate tradeDate = LocalDate.of(2026, 7, 17);
        LocalDateTime asOfTime = tradeDate.atTime(16, 0);
        AiSystemCoreUniverseProvider provider = new AiSystemCoreUniverseProviderImpl(List.of(
                new StubProvider("EASTMONEY", null),
                new StubProvider("SINA_TENCENT", catalog(tradeDate, 220, false))
        ));

        List<AiResearchUniverseService.UniverseCandidate> result = provider.baselineCandidates(
                tradeDate, asOfTime, 200);

        assertThat(result).hasSize(220);
    }

    @Test
    void failsWhenAllProvidersReturnTooFewEligibleSecurities() {
        LocalDate tradeDate = LocalDate.of(2026, 7, 17);
        LocalDateTime asOfTime = tradeDate.atTime(16, 0);
        AiSystemCoreUniverseProvider provider = new AiSystemCoreUniverseProviderImpl(List.of(
                new StubProvider("EASTMONEY", catalog(tradeDate, 50, false))
        ));

        assertThatThrownBy(() -> provider.baselineCandidates(tradeDate, asOfTime, 200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("系统研究池基线股票目录不可用");
    }

    private static HistoricalMarketDataProvider.UniverseCatalog catalog(LocalDate tradeDate, int size, boolean noisy) {
        List<HistoricalMarketDataProvider.Security> securities = new ArrayList<>();
        int perBoard = Math.max(1, size / 4);
        securities.addAll(createBoard("600", "SH主板", perBoard, tradeDate.minusDays(120)));
        securities.addAll(createBoard("000", "SZ主板", perBoard, tradeDate.minusDays(120)));
        securities.addAll(createBoard("300", "创业板", perBoard, tradeDate.minusDays(120)));
        securities.addAll(createBoard("688", "科创板", size - perBoard * 3, tradeDate.minusDays(120)));
        if (noisy) {
            securities.add(new HistoricalMarketDataProvider.Security("600999", "*ST示例", "SH", tradeDate.minusDays(300)));
            securities.add(new HistoricalMarketDataProvider.Security("301999", "新股示例", "SZ", tradeDate.minusDays(10)));
        }
        return new HistoricalMarketDataProvider.UniverseCatalog(
                "EASTMONEY",
                tradeDate.atTime(16, 0),
                "https://push2.eastmoney.com/api/qt/clist/get",
                "catalog-fingerprint-" + size,
                securities
        );
    }

    private static List<HistoricalMarketDataProvider.Security> createBoard(
            String prefix,
            String namePrefix,
            int size,
            LocalDate listedOn
    ) {
        List<HistoricalMarketDataProvider.Security> securities = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            String code = prefix + String.format("%03d", index);
            String market = code.startsWith("6") ? "SH" : "SZ";
            securities.add(new HistoricalMarketDataProvider.Security(code, namePrefix + index, market, listedOn));
        }
        return securities;
    }

    private static final class StubProvider implements HistoricalMarketDataProvider {
        private final String providerCode;
        private final UniverseCatalog catalog;

        private StubProvider(String providerCode, UniverseCatalog catalog) {
            this.providerCode = providerCode;
            this.catalog = catalog;
        }

        @Override
        public String providerCode() {
            return providerCode;
        }

        @Override
        public UniverseCatalog fetchCurrentListedUniverse(int limit, LocalDateTime requestedAt) {
            if (catalog == null) {
                throw new IllegalStateException(providerCode + " unavailable");
            }
            return catalog;
        }

        @Override
        public com.maogou.stock.dto.market.KlineSeriesSnapshot fetchHistoricalKline(
                String symbol,
                int limit,
                LocalDateTime asOfTime,
                String adjustmentMode
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
