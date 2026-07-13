package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.domain.entity.v2.AiTradingCalendar;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.mapper.v2.AiLabelCostEvidenceMapper;
import com.maogou.stock.mapper.v2.AiLabelV2Mapper;
import com.maogou.stock.service.v2.AiLabelServiceV2;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLabelServiceV2ImplTest {

    @Test
    void rejectsUnsupportedLabelHorizons() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiLabelServiceV2.LabelBatch original = batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1");

        AiLabelServiceV2.LabelBatch unsupported = new AiLabelServiceV2.LabelBatch(
                original.inputs(), original.calendars(), original.calendarVersion(),
                original.labelVersion(), List.of(2), original.costModel(), original.verifiedAt());

        assertThatThrownBy(() -> service.verifyAndStore(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("T+1/T+3/T+5");
    }

    @Test
    void rejectsMixedUsersInOneLabelBatch() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiLabelServiceV2.LabelBatch original = batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1");
        AiPredictionV2 otherPrediction = prediction(32L, "600520", "BUY", "UP");
        AiSampleV2 otherSample = sample("600520", "TRADABLE");
        otherPrediction.userId = 6L;
        otherSample.userId = 6L;
        AiLabelServiceV2.LabelInput other = new AiLabelServiceV2.LabelInput(
                otherPrediction, otherSample, stockSeries("600520", normalStockKlines()),
                benchmarkSeries(), sectorSeries());
        AiLabelServiceV2.LabelBatch mixed = new AiLabelServiceV2.LabelBatch(
                List.of(original.inputs().get(0), other), original.calendars(),
                original.calendarVersion(), original.labelVersion(), original.horizons(),
                original.costModel(), original.verifiedAt());

        assertThatThrownBy(() -> service.verifyAndStore(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一用户");
    }

    @Test
    void rejectsMixedTradeDatesAndPhasesInOneLabelBatch() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiLabelServiceV2.LabelBatch original = batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1");
        AiPredictionV2 otherPrediction = prediction(32L, "600520", "BUY", "UP");
        AiSampleV2 otherSample = sample("600520", "TRADABLE");
        otherPrediction.tradeDate = LocalDate.of(2026, 7, 9);
        otherSample.tradeDate = LocalDate.of(2026, 7, 9);
        otherPrediction.samplePhase = "INTRADAY";
        otherSample.samplePhase = "INTRADAY";
        AiLabelServiceV2.LabelInput other = new AiLabelServiceV2.LabelInput(
                otherPrediction, otherSample, stockSeries("600520", normalStockKlines()),
                benchmarkSeries(), sectorSeries());
        AiLabelServiceV2.LabelBatch mixed = new AiLabelServiceV2.LabelBatch(
                List.of(original.inputs().get(0), other), original.calendars(),
                original.calendarVersion(), original.labelVersion(), original.horizons(),
                original.costModel(), original.verifiedAt());

        assertThatThrownBy(() -> service.verifyAndStore(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一交易日和阶段");
    }

    @Test
    void rejectsPredictionAndSamplePointInTimeLineageMismatch() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiPredictionV2 prediction = prediction(31L, "600519", "BUY", "UP");
        AiSampleV2 sample = sample("600519", "TRADABLE");
        sample.tradeDate = LocalDate.of(2026, 7, 9);

        assertThatThrownBy(() -> service.verifyAndStore(batch(
                prediction, sample, stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时点血缘");
    }

    @Test
    void rejectsCalendarRowsWithoutVersionedSourceEvidence() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiLabelServiceV2.LabelBatch invalid = batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1");
        invalid.calendars().get(1).sourceFingerprint = "";

        assertThatThrownBy(() -> service.verifyAndStore(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("交易日历证据");
    }

    @Test
    void rejectsNonDailyOrMockKlineEvidence() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        KlineSeriesSnapshot intraday = KlineSeriesSnapshot.create(
                "600519", "5m", "NONE", "MOCK",
                LocalDateTime.of(2026, 7, 17, 16, 0),
                LocalDateTime.of(2026, 7, 17, 16, 1), normalStockKlines());

        assertThatThrownBy(() -> service.verifyAndStore(batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"), intraday,
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日线");
    }

    @Test
    void doesNotVerifyAHorizonBeforeItsExitSessionCloses() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        LocalDateTime intraday = LocalDateTime.of(2026, 7, 17, 14, 0);
        KlineSeriesSnapshot stock = KlineSeriesSnapshot.create(
                "600519", "day", "NONE", "EASTMONEY", intraday, intraday, normalStockKlines());
        KlineSeriesSnapshot benchmark = KlineSeriesSnapshot.create(
                "000300", "day", "NONE", "EASTMONEY", intraday, intraday,
                benchmarkSeries().points());
        AiLabelServiceV2.LabelBatch original = batch(
                prediction(31L, "600519", "BUY", "UP"), sample("600519", "TRADABLE"),
                stock, benchmark, benchmark, "LABEL_V2_1");
        AiLabelServiceV2.LabelBatch beforeClose = new AiLabelServiceV2.LabelBatch(
                original.inputs(), original.calendars(), original.calendarVersion(),
                original.labelVersion(), original.horizons(), original.costModel(), intraday);

        List<AiLabelV2> labels = service.verifyAndStore(beforeClose);

        assertThat(labels).extracting(item -> item.horizonDays).containsExactly(1, 3);
    }

    @Test
    void watchPredictionIsRecordedAsAbstentionWithoutDirectionHit() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);

        AiLabelV2 label = label(service.verifyAndStore(batch(
                prediction(31L, "600519", "WATCH", "UP"),
                sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1")), 1);

        assertThat(label.actionEvaluation).isEqualTo("ABSTAIN");
        assertThat(label.hitDirection).isNull();
        assertThat(label.hitTarget).isNull();
        assertThat(label.labelScore).isNull();
    }

    @Test
    void rejectsInputsWithoutImmutablePredictionAndSampleFingerprints() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiPredictionV2 prediction = prediction(31L, "600519", "BUY", "UP");
        prediction.inputFingerprint = null;

        assertThatThrownBy(() -> service.verifyAndStore(batch(
                prediction, sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可变指纹");
    }

    @Test
    void detectsCostModelParameterChangesUnderTheSameVersion() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiLabelServiceV2.LabelBatch original = batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1");
        service.verifyAndStore(original);
        AiLabelServiceV2.CostModel changedRates = new AiLabelServiceV2.CostModel(
                original.costModel().version(), new BigDecimal("0.0010"),
                original.costModel().sellCommissionRate(), original.costModel().stampDutyRate(),
                original.costModel().transferFeeRate(), original.costModel().slippageBps(),
                original.costModel().quantity());
        AiLabelServiceV2.LabelBatch tampered = new AiLabelServiceV2.LabelBatch(
                original.inputs(), original.calendars(), original.calendarVersion(),
                original.labelVersion(), original.horizons(), changedRates, original.verifiedAt());

        assertThatThrownBy(() -> service.verifyAndStore(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变标签冲突");
    }

    @Test
    void rejectsKlinePointsThatLeakPastTheSnapshotAsOfTime() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        KlineSeriesSnapshot leaked = KlineSeriesSnapshot.create(
                "600519", "day", "NONE", "EASTMONEY",
                LocalDateTime.of(2026, 7, 15, 16, 0),
                LocalDateTime.of(2026, 7, 17, 16, 1), normalStockKlines());

        assertThatThrownBy(() -> service.verifyAndStore(batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"), leaked,
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asOf");
    }

    @Test
    void calendarSourceEvidenceParticipatesInTheImmutableLabelFingerprint() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiLabelServiceV2.LabelBatch original = batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1");
        service.verifyAndStore(original);
        original.calendars().get(1).sourceFingerprint = "calendar-corrected";

        assertThatThrownBy(() -> service.verifyAndStore(original))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变标签冲突");
    }

    @Test
    void afterClosePredictionUsesNextTradingOpenAndRealT1T3T5TradingDays() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiPredictionV2 prediction = prediction(31L, "600519", "BUY", "UP");
        AiSampleV2 sample = sample("600519", "TRADABLE");

        List<AiLabelV2> labels = service.verifyAndStore(batch(
                prediction,
                sample,
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(),
                sectorSeries(),
                "LABEL_V2_1"));

        assertThat(labels).extracting(item -> item.horizonDays).containsExactly(1, 3, 5);
        assertThat(label(labels, 1).entryTradeDate).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(label(labels, 1).exitTradeDate).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(label(labels, 3).exitTradeDate).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(label(labels, 5).exitTradeDate).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(label(labels, 1).entryPrice).isEqualByComparingTo("10.0000");
        assertThat(label(labels, 1).exitPrice).isEqualByComparingTo("11.0000");
    }

    @Test
    void appliesVersionedCostsAndComputesBenchmarkExcessMfeAndMae() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);

        AiLabelV2 label = label(service.verifyAndStore(batch(
                prediction(31L, "600519", "BUY", "UP"),
                sample("600519", "TRADABLE"),
                stockSeries("600519", normalStockKlines()),
                benchmarkSeries(),
                sectorSeries(),
                "LABEL_V2_1")), 1);

        assertThat(label.grossReturn).isEqualByComparingTo("0.100000");
        assertThat(label.netReturn).isLessThan(label.grossReturn);
        assertThat(label.benchmarkReturn).isEqualByComparingTo("0.010000");
        assertThat(label.excessReturn).isEqualByComparingTo(label.netReturn.subtract(label.benchmarkReturn));
        assertThat(label.maxFavorableReturn).isEqualByComparingTo("0.200000");
        assertThat(label.maxAdverseReturn).isEqualByComparingTo("-0.100000");
        assertThat(label.executionStatus).isEqualTo("EXECUTED");
        verify(fixture.costMapper).insertBatchImmutable(anyList());
    }

    @Test
    void recordsUnexecutableLimitSuspensionAndStStatesInsteadOfFakeReturns() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        List<KlinePointResponse> limitUp = List.of(
                bar("2026-07-10", "10", "10", "10", "10", 100),
                bar("2026-07-13", "11", "11", "11", "11", 100));
        List<KlinePointResponse> suspended = List.of(
                bar("2026-07-10", "10", "10", "10", "10", 100),
                bar("2026-07-13", "10", "10", "10", "10", 0));

        AiLabelV2 blocked = label(service.verifyAndStore(batch(
                prediction(41L, "600001", "BUY", "UP"), sample("600001", "TRADABLE"),
                stockSeries("600001", limitUp), benchmarkSeries(), sectorSeries(), "LABEL_V2_1")), 1);
        AiLabelV2 halted = label(service.verifyAndStore(batch(
                prediction(42L, "600002", "BUY", "UP"), sample("600002", "TRADABLE"),
                stockSeries("600002", suspended), benchmarkSeries(), sectorSeries(), "LABEL_V2_1")), 1);
        AiLabelV2 st = label(service.verifyAndStore(batch(
                prediction(43L, "600003", "BUY", "UP"), sample("600003", "ST_RESTRICTED"),
                stockSeries("600003", normalStockKlines()), benchmarkSeries(), sectorSeries(), "LABEL_V2_1")), 1);

        assertThat(blocked.executionStatus).isEqualTo("LIMIT_UP_ENTRY_BLOCKED");
        assertThat(halted.executionStatus).isEqualTo("SUSPENDED_ENTRY");
        assertThat(st.executionStatus).isEqualTo("ST_RESTRICTED");
        assertThat(List.of(blocked, halted, st)).allMatch(item -> item.netReturn == null);
    }

    @Test
    void detectsALockedLimitDownExitFromTheActualPreviousTradingClose() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        List<KlinePointResponse> points = List.of(
                bar("2026-07-10", "10", "10", "9", "10", 100),
                bar("2026-07-13", "10", "11", "10", "11", 100),
                bar("2026-07-14", "11", "12", "11", "12", 100),
                bar("2026-07-15", "10.8", "10.8", "10.8", "10.8", 100),
                bar("2026-07-16", "10.8", "11", "10", "11", 100),
                bar("2026-07-17", "11", "11", "10", "11", 100));

        AiLabelV2 label = label(service.verifyAndStore(batch(
                prediction(44L, "600004", "BUY", "UP"), sample("600004", "TRADABLE"),
                stockSeries("600004", points), benchmarkSeries(), sectorSeries(), "LABEL_V2_1")), 3);

        assertThat(label.executionStatus).isEqualTo("LIMIT_DOWN_EXIT_BLOCKED");
        assertThat(label.netReturn).isNull();
    }

    @Test
    void sameLabelVersionIsImmutableAndACorrectionCreatesANewVersion() {
        Fixture fixture = fixture();
        AiLabelServiceV2 service = service(fixture);
        AiPredictionV2 prediction = prediction(31L, "600519", "BUY", "UP");
        AiSampleV2 sample = sample("600519", "TRADABLE");

        List<AiLabelV2> first = service.verifyAndStore(batch(
                prediction, sample, stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1"));
        List<AiLabelV2> repeated = service.verifyAndStore(batch(
                prediction, sample, stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_1"));
        List<AiLabelV2> corrected = service.verifyAndStore(batch(
                prediction, sample, stockSeries("600519", normalStockKlines()),
                benchmarkSeries(), sectorSeries(), "LABEL_V2_2"));

        assertThat(repeated).extracting(item -> item.id).containsExactlyElementsOf(
                first.stream().map(item -> item.id).toList());
        assertThat(corrected).extracting(item -> item.id)
                .doesNotContainAnyElementsOf(first.stream().map(item -> item.id).toList());
        verify(fixture.labelMapper, never()).updateById(org.mockito.ArgumentMatchers.any(AiLabelV2.class));
    }

    private static AiLabelServiceV2 service(Fixture fixture) {
        return new AiLabelServiceV2Impl(
                fixture.labelMapper, fixture.costMapper, new ObjectMapper().findAndRegisterModules());
    }

    private static AiLabelServiceV2.LabelBatch batch(
            AiPredictionV2 prediction,
            AiSampleV2 sample,
            KlineSeriesSnapshot stock,
            KlineSeriesSnapshot benchmark,
            KlineSeriesSnapshot sector,
            String labelVersion
    ) {
        AiLabelServiceV2.CostModel costs = new AiLabelServiceV2.CostModel(
                "CN_A_V1", new BigDecimal("0.0003"), new BigDecimal("0.0003"),
                new BigDecimal("0.0005"), new BigDecimal("0.00001"), new BigDecimal("5"),
                new BigDecimal("100"));
        return new AiLabelServiceV2.LabelBatch(
                List.of(new AiLabelServiceV2.LabelInput(prediction, sample, stock, benchmark, sector)),
                calendars(), "SSE_2026_V1", labelVersion, List.of(1, 3, 5), costs,
                LocalDateTime.of(2026, 7, 17, 16, 30));
    }

    private static AiPredictionV2 prediction(Long id, String stockCode, String action, String direction) {
        AiPredictionV2 prediction = new AiPredictionV2();
        prediction.id = id;
        prediction.userId = 5L;
        prediction.sampleId = Long.parseLong(stockCode.substring(stockCode.length() - 3)) + 100L;
        prediction.stockCode = stockCode;
        prediction.tradeDate = LocalDate.of(2026, 7, 10);
        prediction.samplePhase = "AFTER_CLOSE";
        prediction.action = action;
        prediction.actionBucket = "BUY".equals(action) ? "RECOMMEND" : "AVOID";
        prediction.targetDirection = direction;
        prediction.expectedReturn = new BigDecimal("0.03");
        prediction.inputFingerprint = "prediction-" + id;
        return prediction;
    }

    private static AiSampleV2 sample(String stockCode, String tradableStatus) {
        AiSampleV2 sample = new AiSampleV2();
        sample.id = Long.parseLong(stockCode.substring(stockCode.length() - 3)) + 100L;
        sample.userId = 5L;
        sample.stockCode = stockCode;
        sample.stockName = stockCode;
        sample.tradeDate = LocalDate.of(2026, 7, 10);
        sample.samplePhase = "AFTER_CLOSE";
        sample.asOfTime = LocalDateTime.of(2026, 7, 10, 16, 0);
        sample.tradableStatus = tradableStatus;
        sample.sourceFingerprint = "sample-" + stockCode;
        return sample;
    }

    private static List<AiTradingCalendar> calendars() {
        return List.of(
                calendar(1L, "2026-07-10"), calendar(2L, "2026-07-13"),
                calendar(3L, "2026-07-14"), calendar(4L, "2026-07-15"),
                calendar(5L, "2026-07-16"), calendar(6L, "2026-07-17"));
    }

    private static AiTradingCalendar calendar(Long id, String date) {
        AiTradingCalendar calendar = new AiTradingCalendar();
        calendar.id = id;
        calendar.marketCode = "CN_A";
        calendar.tradeDate = LocalDate.parse(date);
        calendar.calendarVersion = "SSE_2026_V1";
        calendar.isTradeDay = 1;
        calendar.sessionOpenTime = LocalTime.of(9, 30);
        calendar.sessionCloseTime = LocalTime.of(15, 0);
        calendar.sourceFingerprint = "calendar-" + date;
        return calendar;
    }

    private static List<KlinePointResponse> normalStockKlines() {
        return List.of(
                bar("2026-07-10", "10", "10", "9", "11", 100),
                bar("2026-07-13", "10", "11", "9", "12", 100),
                bar("2026-07-14", "11", "11.5", "10", "12", 100),
                bar("2026-07-15", "11.5", "12", "11", "13", 100),
                bar("2026-07-16", "12", "12.5", "11.5", "13", 100),
                bar("2026-07-17", "12.5", "13", "12", "14", 100));
    }

    private static KlineSeriesSnapshot benchmarkSeries() {
        return stockSeries("000300", List.of(
                bar("2026-07-10", "100", "100", "99", "101", 100),
                bar("2026-07-13", "100", "101", "99", "102", 100),
                bar("2026-07-14", "101", "102", "100", "103", 100),
                bar("2026-07-15", "102", "103", "101", "104", 100),
                bar("2026-07-16", "103", "104", "102", "105", 100),
                bar("2026-07-17", "104", "105", "103", "106", 100)));
    }

    private static KlineSeriesSnapshot sectorSeries() {
        return benchmarkSeries();
    }

    private static KlineSeriesSnapshot stockSeries(String symbol, List<KlinePointResponse> points) {
        return KlineSeriesSnapshot.create(
                symbol, "day", "NONE", "EASTMONEY", LocalDateTime.of(2026, 7, 17, 16, 0),
                LocalDateTime.of(2026, 7, 17, 16, 1), points);
    }

    private static KlinePointResponse bar(
            String date,
            String open,
            String close,
            String low,
            String high,
            long volume
    ) {
        return new KlinePointResponse(
                LocalDate.parse(date), new BigDecimal(open), new BigDecimal(close),
                new BigDecimal(low), new BigDecimal(high), volume, new BigDecimal("1000000"));
    }

    private static AiLabelV2 label(List<AiLabelV2> labels, int horizon) {
        return labels.stream().filter(item -> item.horizonDays == horizon).findFirst().orElseThrow();
    }

    private static Fixture fixture() {
        AiLabelV2Mapper labelMapper = mock(AiLabelV2Mapper.class);
        AiLabelCostEvidenceMapper costMapper = mock(AiLabelCostEvidenceMapper.class);
        List<AiLabelV2> database = new ArrayList<>();
        AtomicLong ids = new AtomicLong(300);
        when(labelMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiLabelV2> batch = invocation.getArgument(0);
            for (AiLabelV2 candidate : batch) {
                boolean exists = database.stream().anyMatch(item -> item.userId.equals(candidate.userId)
                        && item.predictionId.equals(candidate.predictionId)
                        && item.horizonDays.equals(candidate.horizonDays)
                        && item.labelVersion.equals(candidate.labelVersion));
                if (!exists) {
                    candidate.id = ids.incrementAndGet();
                    database.add(candidate);
                }
            }
            return batch.size();
        });
        when(labelMapper.selectByPredictionIdsForShare(anyLong(), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    Long userId = invocation.getArgument(0);
                    List<Long> predictionIds = invocation.getArgument(1);
                    String version = invocation.getArgument(2);
                    return database.stream().filter(item -> userId.equals(item.userId)
                                    && predictionIds.contains(item.predictionId)
                                    && version.equals(item.labelVersion))
                            .toList();
                });
        when(costMapper.insertBatchImmutable(anyList())).thenReturn(1);
        return new Fixture(labelMapper, costMapper);
    }

    private record Fixture(AiLabelV2Mapper labelMapper, AiLabelCostEvidenceMapper costMapper) {
    }
}
