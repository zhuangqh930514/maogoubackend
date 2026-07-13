package com.maogou.stock.service.impl.v2;

import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.v2.AiFactorValueV2Mapper;
import com.maogou.stock.service.v2.AiFactorEngineV2;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiFactorEngineV2ImplTest {

    @Test
    void preservesTheSignOfContinuousMomentumFactors() {
        AiFactorEngineV2 engine = engine();
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));
        StockDetailResponse detail = detail(List.of(
                kline("2026-07-07", "12"),
                kline("2026-07-08", "11"),
                kline("2026-07-09", "10"),
                kline("2026-07-10", "9")
        ), FinanceSnapshotResponse.empty());

        List<AiFactorValueV2> factors = engine.compute(sample, detail);

        AiFactorValueV2 momentum = factor(factors, "MOMENTUM_RETURN_3D");
        assertThat(momentum.rawValue).isNegative();
        assertThat(momentum.missing).isZero();
    }

    @Test
    void excludesKlinesAfterTheSampleAsOfTime() {
        AiFactorEngineV2 engine = engine();
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));
        StockDetailResponse detail = detail(List.of(
                kline("2026-07-07", "10"),
                kline("2026-07-08", "10"),
                kline("2026-07-09", "10"),
                kline("2026-07-10", "10"),
                kline("2026-07-13", "9999")
        ), FinanceSnapshotResponse.empty());

        List<AiFactorValueV2> factors = engine.compute(sample, detail);

        assertThat(factor(factors, "MOMENTUM_RETURN_3D").rawValue).isEqualByComparingTo("0.00000000");
    }

    @Test
    void excludesSameDayClosingKlineFromIntradaySamples() {
        AiFactorEngineV2 engine = engine();
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));
        sample.samplePhase = "INTRADAY";
        sample.asOfTime = LocalDateTime.of(2026, 7, 10, 14, 0);
        StockDetailResponse detail = detail(List.of(
                kline("2026-07-06", "10"),
                kline("2026-07-07", "10"),
                kline("2026-07-08", "10"),
                kline("2026-07-09", "10"),
                kline("2026-07-10", "99")
        ), FinanceSnapshotResponse.empty());

        List<AiFactorValueV2> factors = engine.compute(sample, detail);

        assertThat(factor(factors, "MOMENTUM_RETURN_3D").rawValue).isEqualByComparingTo("0.00000000");
    }

    @Test
    void recordsMissingFinanceInsteadOfTurningItIntoAZeroSignal() {
        AiFactorEngineV2 engine = engine();
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));
        StockDetailResponse detail = detail(List.of(
                kline("2026-07-09", "10"),
                kline("2026-07-10", "10")
        ), null);

        List<AiFactorValueV2> factors = engine.compute(sample, detail);

        AiFactorValueV2 pe = factor(factors, "FUNDAMENTAL_PE");
        assertThat(pe.missing).isEqualTo(1);
        assertThat(pe.rawValue).isNull();
        assertThat(pe.missingReason).contains("财务");
    }

    @Test
    void recordsPartiallyMissingFinanceFieldsIndividually() {
        AiFactorEngineV2 engine = engine();
        FinanceSnapshotResponse partialFinance = new FinanceSnapshotResponse(
                null, new BigDecimal("2.1"), null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                LocalDate.of(2026, 3, 31), LocalDateTime.of(2026, 4, 25, 18, 0),
                LocalDateTime.of(2026, 7, 10, 15, 55), "EASTMONEY");

        List<AiFactorValueV2> factors = engine.compute(
                sample(LocalDate.of(2026, 7, 10)),
                detail(List.of(kline("2026-07-09", "10"), kline("2026-07-10", "10")), partialFinance));

        assertThat(factor(factors, "FUNDAMENTAL_PE").missing).isEqualTo(1);
        assertThat(factor(factors, "FUNDAMENTAL_PB").missing).isZero();
        assertThat(factor(factors, "FUNDAMENTAL_PB").rawValue).isEqualByComparingTo("2.10000000");
    }

    @Test
    void rejectsFinancePublishedAfterTheSampleTime() {
        AiFactorEngineV2 engine = engine();
        FinanceSnapshotResponse futureFinance = new FinanceSnapshotResponse(
                new BigDecimal("20"), new BigDecimal("2.1"), null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                LocalDate.of(2026, 6, 30), LocalDateTime.of(2026, 7, 11, 18, 0),
                LocalDateTime.of(2026, 7, 11, 18, 1), "EASTMONEY");

        List<AiFactorValueV2> factors = engine.compute(
                sample(LocalDate.of(2026, 7, 10)),
                detail(List.of(kline("2026-07-09", "10"), kline("2026-07-10", "10")), futureFinance));

        AiFactorValueV2 pe = factor(factors, "FUNDAMENTAL_PE");
        assertThat(pe.missing).isEqualTo(1);
        assertThat(pe.missingReason).contains("样本时点");
    }

    @Test
    void preservesNegativeValuationSignalsInsteadOfTreatingThemAsMissing() {
        AiFactorEngineV2 engine = engine();
        FinanceSnapshotResponse lossMakingFinance = new FinanceSnapshotResponse(
                new BigDecimal("-10"), new BigDecimal("-0.5"), null, null, null, null, null, null,
                new BigDecimal("-100"), null, new BigDecimal("-3"), null, null, null, null,
                LocalDate.of(2026, 3, 31), LocalDateTime.of(2026, 4, 25, 18, 0),
                LocalDateTime.of(2026, 7, 10, 15, 55), "EASTMONEY");

        List<AiFactorValueV2> factors = engine.compute(
                sample(LocalDate.of(2026, 7, 10)),
                detail(List.of(kline("2026-07-09", "10"), kline("2026-07-10", "10")), lossMakingFinance));

        assertThat(factor(factors, "FUNDAMENTAL_PE").rawValue).isEqualByComparingTo("-10.00000000");
        assertThat(factor(factors, "FUNDAMENTAL_PB").rawValue).isEqualByComparingTo("-0.50000000");
    }

    @Test
    void keepsPointInTimeValuationWhenTheFinancialReportSourceIsUnavailable() {
        AiFactorEngineV2 engine = engine();
        FinanceSnapshotResponse valuationOnly = new FinanceSnapshotResponse(
                new BigDecimal("20"), new BigDecimal("2.1"), null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, LocalDateTime.of(2026, 7, 10, 15, 55), "EASTMONEY");

        List<AiFactorValueV2> factors = engine.compute(
                sample(LocalDate.of(2026, 7, 10)),
                detail(List.of(kline("2026-07-09", "10"), kline("2026-07-10", "10")), valuationOnly));

        assertThat(factor(factors, "FUNDAMENTAL_PE").missing).isZero();
        assertThat(factor(factors, "FUNDAMENTAL_PB").missing).isZero();
        assertThat(factor(factors, "FUNDAMENTAL_ROE").missing).isEqualTo(1);
    }

    @Test
    void crossSectionNormalizationKeepsNegativeAndPositiveDirection() {
        AiFactorEngineV2 engine = engine();
        List<AiFactorValueV2> values = new ArrayList<>();
        values.add(value("000001", "MOMENTUM_RETURN_3D", "-10"));
        values.add(value("000002", "MOMENTUM_RETURN_3D", "0"));
        values.add(value("000003", "MOMENTUM_RETURN_3D", "10"));

        List<AiFactorValueV2> normalized = engine.normalizeCrossSection(values);

        assertThat(normalized.get(0).normalizedValue).isNegative();
        assertThat(normalized.get(1).normalizedValue).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(normalized.get(2).normalizedValue).isPositive();
    }

    @Test
    void crossSectionNormalizationDoesNotMixFutureSampleTimes() {
        AiFactorEngineV2 engine = engine();
        LocalDateTime currentAsOf = LocalDateTime.of(2026, 7, 10, 16, 0);
        LocalDateTime futureAsOf = currentAsOf.plusDays(1);
        AiFactorValueV2 negative = value("000001", "MOMENTUM_RETURN_3D", "-1", currentAsOf);
        AiFactorValueV2 positive = value("000002", "MOMENTUM_RETURN_3D", "1", currentAsOf);
        AiFactorValueV2 futureOutlier = value("000003", "MOMENTUM_RETURN_3D", "1000", futureAsOf);

        List<AiFactorValueV2> normalized = engine.normalizeCrossSection(
                new ArrayList<>(List.of(negative, positive, futureOutlier)));

        assertThat(normalized.get(0).normalizedValue).isEqualByComparingTo("-1.00000000");
        assertThat(normalized.get(1).normalizedValue).isEqualByComparingTo("1.00000000");
        assertThat(normalized.get(2).normalizedValue).isEqualByComparingTo("0.00000000");
    }

    @Test
    void crossSectionNormalizationDoesNotMixUsersOrUniverses() {
        AiFactorEngineV2 engine = engine();
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 10, 16, 0);
        AiFactorValueV2 firstUser = value("000001", "MOMENTUM_RETURN_3D", "-1", asOf);
        firstUser.userId = 5L;
        firstUser.crossSectionKey = "5:WATCHLIST:A";
        AiFactorValueV2 secondUser = value("000002", "MOMENTUM_RETURN_3D", "1", asOf);
        secondUser.userId = 6L;
        secondUser.crossSectionKey = "6:WATCHLIST:B";

        List<AiFactorValueV2> normalized = engine.normalizeCrossSection(
                new ArrayList<>(List.of(firstUser, secondUser)));

        assertThat(normalized.get(0).normalizedValue).isEqualByComparingTo("0.00000000");
        assertThat(normalized.get(1).normalizedValue).isEqualByComparingTo("0.00000000");
    }

    @Test
    void storesCrossSectionOnlyAfterNormalizationWithoutUpdatingImmutableRows() {
        AiFactorValueV2Mapper mapper = mock(AiFactorValueV2Mapper.class);
        stubBatchPersistence(mapper);
        AiFactorEngineV2 engine = new AiFactorEngineV2Impl(mapper);
        AiFactorValueV2 negative = value("000001", "MOMENTUM_RETURN_3D", "-1");
        negative.sampleId = 11L;
        AiFactorValueV2 positive = value("000002", "MOMENTUM_RETURN_3D", "1");
        positive.sampleId = 12L;

        engine.normalizeAndStoreCrossSection(new ArrayList<>(List.of(negative, positive)));

        assertThat(negative.normalizedValue).isEqualByComparingTo("-1.00000000");
        assertThat(positive.normalizedValue).isEqualByComparingTo("1.00000000");
        verify(mapper).insertBatchImmutable(anyList());
        verify(mapper, never()).updateById(any(AiFactorValueV2.class));
    }

    @Test
    void usesAtomicImmutableBatchUpsertForConcurrentIdempotency() {
        AiFactorValueV2Mapper mapper = mock(AiFactorValueV2Mapper.class);
        stubBatchPersistence(mapper);
        AiFactorEngineV2 engine = new AiFactorEngineV2Impl(mapper);
        AiFactorValueV2 input = value("000001", "MOMENTUM_RETURN_3D", "-1");
        input.sampleId = 11L;

        List<AiFactorValueV2> stored = engine.normalizeAndStoreCrossSection(
                new ArrayList<>(List.of(input)));

        assertThat(stored).singleElement().extracting(value -> value.id).isNotNull();
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void rejectsAnIdempotencyCollisionWithDifferentImmutableContent() {
        AiFactorValueV2Mapper mapper = mock(AiFactorValueV2Mapper.class);
        when(mapper.insertBatchImmutable(anyList())).thenReturn(1);
        AiFactorValueV2 persisted = value("000001", "MOMENTUM_RETURN_3D", "999");
        persisted.id = 99L;
        persisted.sampleId = 11L;
        persisted.factorVersion = AiFactorEngineV2Impl.FACTOR_VERSION;
        persisted.inputFingerprint = "different";
        when(mapper.selectBySamplesForShare(anyList(), anyString())).thenReturn(List.of(persisted));
        AiFactorEngineV2 engine = new AiFactorEngineV2Impl(mapper);
        AiFactorValueV2 input = value("000001", "MOMENTUM_RETURN_3D", "-1");
        input.sampleId = 11L;

        assertThatThrownBy(() -> engine.normalizeAndStoreCrossSection(new ArrayList<>(List.of(input))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变因子冲突");
    }

    @Test
    void computesMarketSectorAndNewsFactorsFromPointInTimeContext() {
        AiFactorEngineV2 engine = engine();
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));
        List<KlinePointResponse> stockKlines = List.of(
                kline("2026-07-07", "10"), kline("2026-07-08", "10"),
                kline("2026-07-09", "10"), kline("2026-07-10", "12"));
        List<KlinePointResponse> marketKlines = List.of(
                kline("2026-07-07", "10"), kline("2026-07-08", "10"),
                kline("2026-07-09", "10"), kline("2026-07-10", "11"));
        List<KlinePointResponse> sectorKlines = List.of(
                kline("2026-07-07", "10"), kline("2026-07-08", "10"),
                kline("2026-07-09", "10"), kline("2026-07-10", "9"));

        List<AiFactorValueV2> factors = engine.compute(new AiFactorEngineV2.FactorContext(
                sample,
                detail(stockKlines, FinanceSnapshotResponse.empty()),
                marketKlines,
                sectorKlines,
                new BigDecimal("0.35"),
                sample.asOfTime.minusMinutes(5)
        ));

        assertThat(factor(factors, "MARKET_RELATIVE_STRENGTH").rawValue)
                .isEqualByComparingTo("0.10000000");
        assertThat(factor(factors, "SECTOR_RELATIVE_STRENGTH").rawValue)
                .isEqualByComparingTo("0.30000000");
        assertThat(factor(factors, "NEWS_SENTIMENT").rawValue)
                .isEqualByComparingTo("0.35000000");
    }

    @Test
    void productionEntryComputesNormalizesAndStoresTheWholeCrossSection() {
        AiFactorValueV2Mapper mapper = mock(AiFactorValueV2Mapper.class);
        stubBatchPersistence(mapper);
        AiFactorEngineV2 engine = new AiFactorEngineV2Impl(mapper);
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        AiSampleV2 fallingSample = sample(tradeDate);
        fallingSample.stockCode = "000001";
        AiSampleV2 risingSample = sample(tradeDate);
        risingSample.id = 9L;
        risingSample.stockCode = "000002";

        List<AiFactorValueV2> stored = engine.computeAndStoreCrossSection(List.of(
                new AiFactorEngineV2.FactorContext(
                        fallingSample,
                        detail(momentumKlines("12", "9"), FinanceSnapshotResponse.empty()),
                        List.of(), List.of(), null, null,
                        series("000001", momentumKlines("12", "9"), fallingSample.asOfTime), null, null),
                new AiFactorEngineV2.FactorContext(
                        risingSample,
                        detail(momentumKlines("9", "12"), FinanceSnapshotResponse.empty()),
                        List.of(), List.of(), null, null,
                        series("000002", momentumKlines("9", "12"), risingSample.asOfTime), null, null)
        ));

        List<AiFactorValueV2> momentum = stored.stream()
                .filter(value -> "MOMENTUM_RETURN_3D".equals(value.factorCode))
                .toList();
        assertThat(momentum).hasSize(2);
        assertThat(momentum.get(0).normalizedValue).isNegative();
        assertThat(momentum.get(1).normalizedValue).isPositive();
        verify(mapper).insertBatchImmutable(anyList());
    }

    @Test
    void productionEntryRejectsAdjustedOrUnversionedKlines() {
        AiFactorValueV2Mapper mapper = mock(AiFactorValueV2Mapper.class);
        AiFactorEngineV2 engine = new AiFactorEngineV2Impl(mapper);
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));

        assertThatThrownBy(() -> engine.computeAndStoreCrossSection(List.of(
                new AiFactorEngineV2.FactorContext(
                        sample,
                        detail(momentumKlines("10", "11"), FinanceSnapshotResponse.empty()),
                        List.of(), List.of(), null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未复权");
    }

    @Test
    void productionEntryRejectsUnversionedRawBenchmarkLists() {
        AiFactorValueV2Mapper mapper = mock(AiFactorValueV2Mapper.class);
        AiFactorEngineV2 engine = new AiFactorEngineV2Impl(mapper);
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));
        List<KlinePointResponse> points = momentumKlines("10", "11");

        assertThatThrownBy(() -> engine.computeAndStoreCrossSection(List.of(
                new AiFactorEngineV2.FactorContext(
                        sample, detail(points, FinanceSnapshotResponse.empty()),
                        points, List.of(), null, null,
                        series("600519", points, sample.asOfTime), null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("市场");
    }

    @Test
    void productionEntryBindsTheStockSnapshotToSampleAndDailyPeriod() {
        AiFactorValueV2Mapper mapper = mock(AiFactorValueV2Mapper.class);
        AiFactorEngineV2 engine = new AiFactorEngineV2Impl(mapper);
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));
        List<KlinePointResponse> points = momentumKlines("10", "11");

        assertThatThrownBy(() -> engine.computeAndStoreCrossSection(List.of(
                new AiFactorEngineV2.FactorContext(
                        sample, detail(points, FinanceSnapshotResponse.empty()),
                        List.of(), List.of(), null, null,
                        series("000001", points, sample.asOfTime), null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("股票代码");
    }

    @Test
    void relativeStrengthRequiresAlignedTradingDates() {
        AiFactorEngineV2 engine = engine();
        AiSampleV2 sample = sample(LocalDate.of(2026, 7, 10));
        List<KlinePointResponse> stockKlines = List.of(
                kline("2026-07-07", "10"), kline("2026-07-08", "10"),
                kline("2026-07-09", "10"), kline("2026-07-10", "12"));
        List<KlinePointResponse> benchmarkWithGap = List.of(
                kline("2026-07-06", "10"), kline("2026-07-07", "10"),
                kline("2026-07-08", "10"), kline("2026-07-10", "11"));

        List<AiFactorValueV2> factors = engine.compute(new AiFactorEngineV2.FactorContext(
                sample, detail(stockKlines, FinanceSnapshotResponse.empty()),
                benchmarkWithGap, List.of(), null, null));

        assertThat(factor(factors, "MARKET_RELATIVE_STRENGTH").missing).isEqualTo(1);
    }

    private static AiFactorEngineV2 engine() {
        AiFactorValueV2Mapper mapper = mock(AiFactorValueV2Mapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        return new AiFactorEngineV2Impl(mapper);
    }

    private static void stubBatchPersistence(AiFactorValueV2Mapper mapper) {
        List<AiFactorValueV2> database = new ArrayList<>();
        AtomicLong ids = new AtomicLong(100);
        when(mapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiFactorValueV2> batch = invocation.getArgument(0);
            for (AiFactorValueV2 value : batch) {
                value.id = ids.incrementAndGet();
                database.add(value);
            }
            return batch.size();
        });
        when(mapper.selectBySamplesForShare(anyList(), anyString())).thenAnswer(invocation -> {
            List<Long> sampleIds = invocation.getArgument(0);
            String version = invocation.getArgument(1);
            return database.stream()
                    .filter(value -> sampleIds.contains(value.sampleId) && version.equals(value.factorVersion))
                    .toList();
        });
    }

    private static AiSampleV2 sample(LocalDate tradeDate) {
        AiSampleV2 sample = new AiSampleV2();
        sample.id = 8L;
        sample.userId = 5L;
        sample.stockCode = "600519";
        sample.asOfTime = tradeDate.atTime(16, 0);
        return sample;
    }

    private static StockDetailResponse detail(List<KlinePointResponse> klines, FinanceSnapshotResponse finance) {
        StockQuoteResponse quote = new StockQuoteResponse(
                "600519", "贵州茅台", new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, "SH", "TENCENT", LocalDateTime.of(2026, 7, 10, 16, 0));
        return new StockDetailResponse(quote, finance, List.of(), klines, "", 0);
    }

    private static KlinePointResponse kline(String date, String close) {
        BigDecimal price = new BigDecimal(close);
        return new KlinePointResponse(
                LocalDate.parse(date), price, price, price, price, 100L, new BigDecimal("100000000"));
    }

    private static List<KlinePointResponse> momentumKlines(String first, String last) {
        return List.of(
                kline("2026-07-07", first),
                kline("2026-07-08", "10"),
                kline("2026-07-09", "10"),
                kline("2026-07-10", last)
        );
    }

    private static KlineSeriesSnapshot series(
            String symbol,
            List<KlinePointResponse> points,
            LocalDateTime asOfTime
    ) {
        return KlineSeriesSnapshot.create(
                symbol, "day", "NONE", "EASTMONEY", asOfTime, asOfTime.minusSeconds(1), points);
    }

    private static AiFactorValueV2 value(String stockCode, String factorCode, String rawValue) {
        return value(stockCode, factorCode, rawValue, LocalDateTime.of(2026, 7, 10, 16, 0));
    }

    private static AiFactorValueV2 value(
            String stockCode,
            String factorCode,
            String rawValue,
            LocalDateTime calculatedAt
    ) {
        AiFactorValueV2 value = new AiFactorValueV2();
        value.userId = 5L;
        value.stockCode = stockCode;
        value.factorCode = factorCode;
        value.factorVersion = AiFactorEngineV2Impl.FACTOR_VERSION;
        value.factorGroup = "TEST";
        value.rawValue = new BigDecimal(rawValue);
        value.direction = value.rawValue.signum() < 0 ? "NEGATIVE" : "POSITIVE";
        value.hit = 0;
        value.missing = 0;
        value.evidence = "test";
        value.calculatedAt = calculatedAt;
        value.createdAt = LocalDateTime.of(2026, 7, 10, 16, 0);
        return value;
    }

    private static AiFactorValueV2 factor(List<AiFactorValueV2> factors, String code) {
        return factors.stream().filter(item -> code.equals(item.factorCode)).findFirst().orElseThrow();
    }
}
