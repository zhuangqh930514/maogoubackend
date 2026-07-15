package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.mapper.research.AiSourceObservationMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.research.HistoricalUniverseSourceService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalUniverseSourceServiceImplTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2025, 1, 6);
    private static final LocalDateTime AS_OF = TRADE_DATE.atTime(16, 0);

    @Test
    void reportsMissingHistoricalUniverseInsteadOfFallingBackToCurrentConstituents() {
        Fixture fixture = fixture();
        when(fixture.calendarMapper.selectByDates(any(), any(), any())).thenReturn(List.of(calendar(1)));
        when(fixture.snapshotMapper.selectOne(any())).thenReturn(null);

        HistoricalUniverseSourceService.HistoricalDayEvidence result = service(fixture).load(TRADE_DATE, AS_OF);

        assertThat(result.status()).isEqualTo("MISSING_HISTORICAL_UNIVERSE");
        assertThat(result.missingEvidence()).anyMatch(reason -> reason.contains("历史股票池"));
        assertThat(result.universeSnapshotId()).isNull();
    }

    @Test
    void rejectsListingOrIndustryEvidenceThatWasNotVisibleAtTheReplayTime() {
        Fixture fixture = readyFixture();
        AiResearchUniverseItem item = item();
        item.effectiveFrom = TRADE_DATE.plusDays(1);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(item));

        HistoricalUniverseSourceService.HistoricalDayEvidence result = service(fixture).load(TRADE_DATE, AS_OF);

        assertThat(result.status()).isEqualTo("MISSING_HISTORICAL_UNIVERSE");
        assertThat(result.missingEvidence()).anyMatch(reason -> reason.contains("上市状态"));
    }

    @Test
    void acceptsOnlyCompleteRealSourcePointInTimeEvidence() {
        Fixture fixture = readyFixture();
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(item()));
        when(fixture.observationMapper.selectList(any())).thenReturn(observations(false));

        HistoricalUniverseSourceService.HistoricalDayEvidence result = service(fixture).load(TRADE_DATE, AS_OF);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.stockCount()).isEqualTo(1);
        assertThat(result.sourceFingerprint()).hasSize(64);
    }

    @Test
    void rejectsLateOrMockAdjustmentEvidence() {
        Fixture fixture = readyFixture();
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(item()));
        when(fixture.observationMapper.selectList(any())).thenReturn(observations(true));

        HistoricalUniverseSourceService.HistoricalDayEvidence result = service(fixture).load(TRADE_DATE, AS_OF);

        assertThat(result.status()).isEqualTo("MISSING_HISTORICAL_UNIVERSE");
        assertThat(result.missingEvidence()).anyMatch(reason -> reason.contains("复权因子"));
    }

    private static HistoricalUniverseSourceService service(Fixture fixture) {
        return new HistoricalUniverseSourceServiceImpl(
                fixture.calendarMapper, fixture.snapshotMapper, fixture.itemMapper,
                fixture.batchMapper, fixture.observationMapper);
    }

    private static Fixture readyFixture() {
        Fixture fixture = fixture();
        when(fixture.calendarMapper.selectByDates(any(), any(), any())).thenReturn(List.of(calendar(1)));
        AiResearchUniverseSnapshot snapshot = new AiResearchUniverseSnapshot();
        snapshot.id = 11L;
        snapshot.tradeDate = TRADE_DATE;
        snapshot.asOfTime = AS_OF;
        snapshot.qualityStatus = "READY";
        snapshot.status = "FINALIZED";
        snapshot.sourceFingerprint = "universe-fingerprint";
        when(fixture.snapshotMapper.selectOne(any())).thenReturn(snapshot);
        AiDataBatch batch = new AiDataBatch();
        batch.id = 21L;
        batch.universeSnapshotId = 11L;
        batch.tradeDate = TRADE_DATE;
        batch.asOfTime = AS_OF;
        batch.qualityStatus = "READY";
        batch.status = "SUCCESS";
        when(fixture.batchMapper.selectOne(any())).thenReturn(batch);
        return fixture;
    }

    private static Fixture fixture() {
        return new Fixture(
                mock(AiTradingCalendarMapper.class),
                mock(AiResearchUniverseSnapshotMapper.class),
                mock(AiResearchUniverseItemMapper.class),
                mock(AiDataBatchMapper.class),
                mock(AiSourceObservationMapper.class));
    }

    private static AiTradingCalendar calendar(int tradeDay) {
        AiTradingCalendar value = new AiTradingCalendar();
        value.id = 1L;
        value.marketCode = "CN_A";
        value.tradeDate = TRADE_DATE;
        value.calendarVersion = "CN_A_SHARE/1.0.0";
        value.isTradeDay = tradeDay;
        value.sourceName = "SSE_CALENDAR";
        value.sourceAsOf = TRADE_DATE.minusDays(30).atStartOfDay();
        value.sourceFingerprint = "calendar-fingerprint";
        return value;
    }

    private static AiResearchUniverseItem item() {
        AiResearchUniverseItem item = new AiResearchUniverseItem();
        item.id = 31L;
        item.universeSnapshotId = 11L;
        item.stockCode = "600519";
        item.stockName = "贵州茅台";
        item.market = "SH";
        item.listedStatus = "LISTED";
        item.included = 1;
        item.effectiveFrom = TRADE_DATE.minusYears(20);
        item.sourceFingerprint = "item-fingerprint";
        return item;
    }

    private static List<AiSourceObservation> observations(boolean invalidAdjustment) {
        List<AiSourceObservation> values = new ArrayList<>();
        values.add(observation(null, "MARKET_BENCHMARK", "EASTMONEY", AS_OF.minusMinutes(5)));
        values.add(observation("600519", "STOCK_DAILY_SNAPSHOT", "EASTMONEY", AS_OF.minusMinutes(5)));
        values.add(observation("600519", "ADJUSTMENT_FACTOR",
                invalidAdjustment ? "MOCK" : "EASTMONEY",
                invalidAdjustment ? AS_OF.plusMinutes(1) : AS_OF.minusMinutes(5)));
        values.add(observation("600519", "INDUSTRY_MEMBERSHIP", "EASTMONEY", AS_OF.minusMinutes(5)));
        values.add(observation("600519", "INDUSTRY_BENCHMARK", "EASTMONEY", AS_OF.minusMinutes(5)));
        return values;
    }

    private static AiSourceObservation observation(
            String stockCode,
            String sourceType,
            String provider,
            LocalDateTime availableAt
    ) {
        AiSourceObservation value = new AiSourceObservation();
        value.id = (long) Math.abs((stockCode + sourceType).hashCode());
        value.dataBatchId = 21L;
        value.stockCode = stockCode;
        value.sourceType = sourceType;
        value.providerCode = provider;
        value.endpointType = sourceType;
        value.eventTime = TRADE_DATE.atTime(15, 0);
        value.firstSeenAt = availableAt;
        value.fetchedAt = availableAt;
        value.asOfTime = availableAt;
        value.availableAt = availableAt;
        value.observedAt = availableAt;
        value.sourceRevision = TRADE_DATE.toString();
        value.payloadJson = "{}";
        value.payloadChecksum = "checksum-" + sourceType;
        value.sourceFingerprint = "fingerprint-" + sourceType;
        value.freshnessStatus = "REALTIME";
        value.qualityStatus = "READY";
        return value;
    }

    private record Fixture(
            AiTradingCalendarMapper calendarMapper,
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            AiDataBatchMapper batchMapper,
            AiSourceObservationMapper observationMapper
    ) {
    }
}
