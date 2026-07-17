package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiResearchUniverse;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiSourceObservationMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSampleSnapshotService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiHistoricalEvidenceImportServiceImplTest {

    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 16);
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 7, 16, 18, 0);

    @Test
    void plansTrainingDaysWithFiveTradingDayMaturityBuffer() {
        Fixture fixture = fixture();
        List<AiTradingCalendar> calendars = calendars(125);
        when(fixture.calendarMapper.selectRecentTradingDays(any(), any(), eq(125))).thenReturn(calendars);

        AiHistoricalEvidenceImportService.ColdStartPlan plan = service(fixture).plan(END_DATE, 120, 200);

        assertThat(plan.trainingTradingDays()).isEqualTo(120);
        assertThat(plan.replayTradingDays()).isEqualTo(125);
        assertThat(plan.targetStockCount()).isEqualTo(200);
        assertThat(plan.tradingDates()).hasSize(125).isSorted();
        assertThat(plan.startDate()).isEqualTo(plan.tradingDates().get(0));
        assertThat(plan.endDate()).isEqualTo(END_DATE);
    }

    @Test
    void seedsMissingTradingCalendarFromRealBenchmarkHistory() {
        Fixture fixture = fixture();
        List<AiTradingCalendar> complete = calendars(125);
        when(fixture.calendarMapper.selectRecentTradingDays(any(), any(), eq(125)))
                .thenReturn(List.of(), complete);
        when(fixture.provider.fetchHistoricalKline(
                eq("000300.SH"), anyInt(), any(), eq("NONE")))
                .thenReturn(calendarSeries(END_DATE, 165));

        AiHistoricalEvidenceImportService.ColdStartPlan plan = service(fixture).plan(
                END_DATE, 120, 200);

        assertThat(plan.tradingDates()).hasSize(125);
        verify(fixture.calendarMapper, org.mockito.Mockito.atLeast(125)).insertIgnore(any());
    }

    @Test
    void switchesToSecondaryRealProviderAfterPrimarySourceFailure() {
        Fixture fixture = fixture();
        HistoricalMarketDataProvider primary = mock(HistoricalMarketDataProvider.class);
        HistoricalMarketDataProvider secondary = mock(HistoricalMarketDataProvider.class);
        when(primary.providerCode()).thenReturn("EASTMONEY");
        when(secondary.providerCode()).thenReturn("SINA_TENCENT");
        when(fixture.calendarMapper.selectRecentTradingDays(any(), any(), eq(125)))
                .thenReturn(List.of(), calendars(125));
        when(primary.fetchHistoricalKline(any(), anyInt(), any(), eq("NONE")))
                .thenThrow(new IllegalStateException("Unexpected end of file from server"));
        when(secondary.fetchHistoricalKline(any(), anyInt(), any(), eq("NONE")))
                .thenReturn(calendarSeries(END_DATE, 165));
        AiHistoricalEvidenceImportService service = new AiHistoricalEvidenceImportServiceImpl(
                fixture.calendarMapper,
                List.of(primary, secondary),
                fixture.universeService,
                fixture.snapshotService,
                fixture.dataBatchMapper,
                fixture.observationMapper,
                new ObjectMapper().findAndRegisterModules());

        AiHistoricalEvidenceImportService.ColdStartPlan plan = service.plan(END_DATE, 120, 200);

        assertThat(plan.tradingDates()).hasSize(125);
        verify(primary).fetchHistoricalKline(any(), anyInt(), any(), eq("NONE"));
        verify(secondary).fetchHistoricalKline(any(), anyInt(), any(), eq("NONE"));
    }

    @Test
    void importsPointInTimeStockBenchmarkAndAdjustmentEvidence() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 1, 5);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                tradeDate, tradeDate, 1, 1, 2, List.of(tradeDate));
        when(fixture.provider.fetchCurrentListedUniverse(anyInt(), any())).thenReturn(catalog(tradeDate));
        when(fixture.provider.fetchHistoricalKline(any(), anyInt(), any(), eq("NONE")))
                .thenAnswer(invocation -> series(invocation.getArgument(0), tradeDate, "NONE"));
        when(fixture.provider.fetchHistoricalKline(any(), anyInt(), any(), eq("QFQ")))
                .thenAnswer(invocation -> series(invocation.getArgument(0), tradeDate, "QFQ"));
        when(fixture.universeService.createSystemCoreSnapshot(any())).thenAnswer(invocation -> {
            AiResearchUniverseService.SnapshotRequest request = invocation.getArgument(0);
            AiResearchUniverse universe = new AiResearchUniverse();
            universe.id = 1L;
            AiResearchUniverseSnapshot snapshot = new AiResearchUniverseSnapshot();
            snapshot.id = 11L;
            snapshot.tradeDate = tradeDate;
            snapshot.asOfTime = tradeDate.atTime(16, 0);
            snapshot.qualityStatus = "READY";
            snapshot.sourceFingerprint = "universe-fingerprint";
            List<AiResearchUniverseItem> items = new ArrayList<>();
            long id = 20L;
            for (AiResearchUniverseService.UniverseCandidate candidate : request.configuredComponents()) {
                AiResearchUniverseItem item = new AiResearchUniverseItem();
                item.id = id++;
                item.universeSnapshotId = snapshot.id;
                item.stockCode = candidate.stockCode();
                item.stockName = candidate.stockName();
                item.market = candidate.market();
                item.included = 1;
                item.effectiveFrom = candidate.effectiveFrom();
                items.add(item);
            }
            return new AiResearchUniverseService.SnapshotResult(universe, snapshot, items, false);
        });
        AiDataBatch batch = new AiDataBatch();
        batch.id = 31L;
        batch.qualityStatus = "UNAVAILABLE";
        batch.status = "RUNNING";
        when(fixture.snapshotService.startOrGetBatch(any(), any(), any(), any(), any())).thenReturn(batch);
        when(fixture.observationMapper.insert(any(AiSourceObservation.class))).thenAnswer(invocation -> {
            AiSourceObservation observation = invocation.getArgument(0);
            observation.id = fixture.ids.incrementAndGet();
            return 1;
        });
        when(fixture.snapshotService.completeBatch(any(), any())).thenAnswer(invocation -> batch);

        AiHistoricalEvidenceImportService.ImportResult result = service(fixture).importEvidence(
                new AiHistoricalEvidenceImportService.ImportRequest(plan, "TEST:IMPORT", REQUESTED_AT));

        assertThat(result.importedTradingDays()).isEqualTo(1);
        assertThat(result.preparedStocks()).isEqualTo(2);
        ArgumentCaptor<AiSourceObservation> observations = ArgumentCaptor.forClass(AiSourceObservation.class);
        verify(fixture.observationMapper, org.mockito.Mockito.times(5)).insert(observations.capture());
        assertThat(observations.getAllValues()).extracting(value -> value.sourceType)
                .containsExactlyInAnyOrder(
                        "MARKET_BENCHMARK",
                        "STOCK_DAILY_SNAPSHOT", "ADJUSTMENT_FACTOR",
                        "STOCK_DAILY_SNAPSHOT", "ADJUSTMENT_FACTOR");
        assertThat(observations.getAllValues()).allSatisfy(observation -> {
            assertThat(observation.asOfTime).isEqualTo(tradeDate.atTime(16, 0));
            assertThat(observation.fetchedAt).isEqualTo(REQUESTED_AT);
            assertThat(observation.availableAt).isBeforeOrEqualTo(observation.asOfTime);
            assertThat(observation.providerCode).doesNotContainIgnoringCase("MOCK");
        });
        verify(fixture.dataBatchMapper).updateById(batch);
    }

    @Test
    void reusesCompletedHistoricalBatchWithoutDuplicatingEvidence() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 1, 5);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                tradeDate, tradeDate, 1, 1, 2, List.of(tradeDate));
        when(fixture.provider.fetchCurrentListedUniverse(anyInt(), any())).thenReturn(catalog(tradeDate));
        when(fixture.provider.fetchHistoricalKline(any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> series(invocation.getArgument(0), tradeDate, invocation.getArgument(3)));
        when(fixture.universeService.createSystemCoreSnapshot(any())).thenReturn(snapshotResult(tradeDate));
        AiDataBatch batch = new AiDataBatch();
        batch.id = 31L;
        batch.qualityStatus = "READY";
        batch.status = "SUCCESS";
        batch.itemCount = 2;
        batch.successCount = 2;
        when(fixture.snapshotService.startOrGetBatch(any(), any(), any(), any(), any())).thenReturn(batch);

        AiHistoricalEvidenceImportService.ImportResult result = service(fixture).importEvidence(
                new AiHistoricalEvidenceImportService.ImportRequest(plan, "TEST:REUSE", REQUESTED_AT));

        assertThat(result.reusedTradingDays()).isEqualTo(1);
        verify(fixture.observationMapper, never()).insert(any(AiSourceObservation.class));
        verify(fixture.snapshotService, never()).completeBatch(any(), any());
    }

    private static AiHistoricalEvidenceImportService service(Fixture fixture) {
        return new AiHistoricalEvidenceImportServiceImpl(
                fixture.calendarMapper,
                fixture.provider,
                fixture.universeService,
                fixture.snapshotService,
                fixture.dataBatchMapper,
                fixture.observationMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    private static Fixture fixture() {
        return new Fixture(
                mock(AiTradingCalendarMapper.class),
                mock(HistoricalMarketDataProvider.class),
                mock(AiResearchUniverseService.class),
                mock(AiSampleSnapshotService.class),
                mock(AiDataBatchMapper.class),
                mock(AiSourceObservationMapper.class),
                new AtomicLong(100));
    }

    private static HistoricalMarketDataProvider.UniverseCatalog catalog(LocalDate listedOn) {
        return new HistoricalMarketDataProvider.UniverseCatalog(
                "EASTMONEY",
                REQUESTED_AT,
                "https://push2.eastmoney.com/api/qt/clist/get",
                "catalog-fingerprint",
                List.of(
                        new HistoricalMarketDataProvider.Security("000001", "平安银行", "SZ", listedOn.minusYears(10)),
                        new HistoricalMarketDataProvider.Security("600519", "贵州茅台", "SH", listedOn.minusYears(20))));
    }

    private static KlineSeriesSnapshot series(String code, LocalDate date, String adjustmentMode) {
        List<KlinePointResponse> points = new ArrayList<>();
        for (int index = 25; index >= 0; index--) {
            LocalDate pointDate = date.minusDays(index);
            BigDecimal close = BigDecimal.valueOf(10 + (25 - index) * 0.1);
            points.add(new KlinePointResponse(
                    pointDate, close, close, close, close, 100_000L, BigDecimal.valueOf(1_000_000)));
        }
        return KlineSeriesSnapshot.create(
                code, "day", adjustmentMode, "EASTMONEY", date.atTime(16, 0), REQUESTED_AT, points);
    }

    private static KlineSeriesSnapshot calendarSeries(LocalDate endDate, int count) {
        List<KlinePointResponse> points = new ArrayList<>();
        LocalDate cursor = endDate;
        while (points.size() < count) {
            if (cursor.getDayOfWeek().getValue() <= 5) {
                points.add(new KlinePointResponse(
                        cursor, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                        BigDecimal.TEN, 100_000L, BigDecimal.valueOf(1_000_000)));
            }
            cursor = cursor.minusDays(1);
        }
        points.sort(java.util.Comparator.comparing(KlinePointResponse::tradeDate));
        return KlineSeriesSnapshot.create(
                "000300.SH", "day", "NONE", "EASTMONEY",
                endDate.atTime(16, 0), REQUESTED_AT, points);
    }

    private static List<AiTradingCalendar> calendars(int count) {
        List<AiTradingCalendar> result = new ArrayList<>();
        LocalDate cursor = END_DATE;
        while (result.size() < count) {
            if (cursor.getDayOfWeek().getValue() <= 5) {
                AiTradingCalendar calendar = new AiTradingCalendar();
                calendar.id = (long) result.size() + 1;
                calendar.marketCode = "CN_A_SHARE";
                calendar.tradeDate = cursor;
                calendar.calendarVersion = "CN_A_SHARE/1.0.0";
                calendar.isTradeDay = 1;
                calendar.sourceName = "EASTMONEY";
                calendar.sourceFingerprint = "calendar-" + cursor;
                result.add(calendar);
            }
            cursor = cursor.minusDays(1);
        }
        return result;
    }

    private static AiResearchUniverseService.SnapshotResult snapshotResult(LocalDate date) {
        AiResearchUniverse universe = new AiResearchUniverse();
        universe.id = 1L;
        AiResearchUniverseSnapshot snapshot = new AiResearchUniverseSnapshot();
        snapshot.id = 11L;
        snapshot.tradeDate = date;
        snapshot.asOfTime = date.atTime(16, 0);
        snapshot.qualityStatus = "READY";
        snapshot.sourceFingerprint = "universe-fingerprint";
        AiResearchUniverseItem first = new AiResearchUniverseItem();
        first.id = 21L;
        first.stockCode = "000001";
        first.stockName = "平安银行";
        first.included = 1;
        AiResearchUniverseItem second = new AiResearchUniverseItem();
        second.id = 22L;
        second.stockCode = "600519";
        second.stockName = "贵州茅台";
        second.included = 1;
        return new AiResearchUniverseService.SnapshotResult(universe, snapshot, List.of(first, second), false);
    }

    private record Fixture(
            AiTradingCalendarMapper calendarMapper,
            HistoricalMarketDataProvider provider,
            AiResearchUniverseService universeService,
            AiSampleSnapshotService snapshotService,
            AiDataBatchMapper dataBatchMapper,
            AiSourceObservationMapper observationMapper,
            AtomicLong ids
    ) {
    }
}
