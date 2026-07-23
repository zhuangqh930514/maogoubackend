package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiIndustryDailyBar;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiIndustryDailyBarMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiSecurityDailyStateMapper;
import com.maogou.stock.mapper.research.AiSourceObservationMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import com.maogou.stock.service.research.AiPredictionEvaluationService;
import com.maogou.stock.service.research.AiSampleLabelService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLabelVerificationCoordinatorImplTest {

    private static final LocalDateTime EVIDENCE_VERIFIED_AT =
            LocalDateTime.of(2026, 7, 10, 16, 0, 5);

    @Test
    void maturesMarketLabelsOnceAndKeepsPerStockFailuresAsWarnings() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(
                sample(21L, "600519"), sample(23L, "600519"), sample(22L, "300058")));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.marketDataService.klineAt("300058", "day", 320, verifiedAt))
                .thenThrow(new IllegalStateException("K线暂不可用"));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        AiSampleLabel label = new AiSampleLabel();
        label.id = 81L;
        label.sampleId = 21L;
        label.labelStatus = "MATURED";
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of(label));

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);

        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.errors()).singleElement().asString()
                .contains("300058:")
                .contains("K线暂不可用");
        ArgumentCaptor<AiSampleLabelService.LabelBatch> batchCaptor =
                ArgumentCaptor.forClass(AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(batchCaptor.capture());
        assertThat(batchCaptor.getValue().verifiedAt()).isEqualTo(EVIDENCE_VERIFIED_AT);
        assertThat(batchCaptor.getValue().samples()).hasSize(2);
        assertThat(batchCaptor.getValue().samples())
                .allSatisfy(input -> assertThat(input.stockSeries().fetchedAt())
                        .isBeforeOrEqualTo(batchCaptor.getValue().verifiedAt()));
        verify(fixture.evaluationService, never()).evaluateAndStore(any());
        verify(fixture.marketDataService, times(1)).klineAt("600519", "day", 320, verifiedAt);
    }

    @Test
    void countsAllStoredLabelsAsProcessedWork() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample(21L, "600519")));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        AiSampleLabel label1 = new AiSampleLabel();
        label1.id = 81L;
        label1.sampleId = 21L;
        label1.labelStatus = "MATURED";
        AiSampleLabel label2 = new AiSampleLabel();
        label2.id = 82L;
        label2.sampleId = 21L;
        label2.labelStatus = "MATURED";
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of(label1, label2));

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);

        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void fallsBackToHistoricalKlineWhenRealtimeResearchSeriesUnavailable() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample(21L, "600519")));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenThrow(new IllegalStateException("正式研究 K线不可用"));
        when(fixture.historicalProvider.fetchHistoricalKline("600519", 320, verifiedAt, "NONE"))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        AiSampleLabel label = new AiSampleLabel();
        label.id = 81L;
        label.sampleId = 21L;
        label.labelStatus = "MATURED";
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of(label));

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);
        assertThat(result.processedCount()).withFailMessage(result.toString()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(result.errors()).isEmpty();
        verify(fixture.historicalProvider).fetchHistoricalKline("600519", 320, verifiedAt, "NONE");
    }

    @Test
    void fallsBackToPersistedDailySnapshotsWhenAllExternalSourcesFail() throws Exception {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample sample = sample(21L, "600519");
        sample.dataBatchId = 88L;
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenThrow(new IllegalStateException("正式研究 K线不可用"));
        when(fixture.historicalProvider.fetchHistoricalKline("600519", 320, verifiedAt, "NONE"))
                .thenThrow(new IllegalStateException("历史回退也不可用"));
        when(fixture.observationMapper.selectReadyDailySnapshotByBatch(88L, "600519"))
                .thenReturn(snapshotObservation(88L, "600519", LocalDate.of(2026, 7, 3)));
        when(fixture.observationMapper.selectReadyDailySnapshotsBetween(
                "600519", LocalDate.of(2026, 7, 3).atTime(15, 0), verifiedAt))
                .thenReturn(List.of(
                        snapshotObservation(89L, "600519", LocalDate.of(2026, 7, 8)),
                        snapshotObservation(90L, "600519", LocalDate.of(2026, 7, 9))));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        AiSampleLabel label = new AiSampleLabel();
        label.id = 81L;
        label.sampleId = 21L;
        label.labelStatus = "MATURED";
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of(label));

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);

        verify(fixture.labelService).matureAndStore(any());
        assertThat(result.processedCount()).withFailMessage(result.toString()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        verify(fixture.observationMapper).selectReadyDailySnapshotByBatch(88L, "600519");
        verify(fixture.observationMapper).selectReadyDailySnapshotsBetween(
                "600519", LocalDate.of(2026, 7, 3).atTime(15, 0), verifiedAt);
    }

    @Test
    void attachesPointInTimeSectorBenchmarkEvidenceToLabelInputs() throws Exception {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample sample = sample(21L, "600519");
        sample.dataBatchId = 88L;
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        AiSourceObservation anchor = sectorObservation(
                88L, "600519", "BK0475", LocalDateTime.of(2026, 7, 3, 16, 0),
                List.of(point(LocalDate.of(2026, 7, 2)), point(LocalDate.of(2026, 7, 3))));
        AiSourceObservation subsequent = sectorObservation(
                89L, "600519", "BK0475", LocalDateTime.of(2026, 7, 8, 16, 0),
                List.of(point(LocalDate.of(2026, 7, 7)), point(LocalDate.of(2026, 7, 8))));
        when(fixture.observationMapper.selectReadyIndustryMembershipByBatch(88L, "600519"))
                .thenReturn(membershipObservation(88L, "600519"));
        when(fixture.observationMapper.selectReadyIndustryBenchmarkByBatch(88L, "600519"))
                .thenReturn(anchor);
        when(fixture.observationMapper.selectRecentReadyIndustryBenchmarksBetween(
                "600519", anchor.asOfTime, verifiedAt)).thenReturn(List.of(subsequent));
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of());

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);

        ArgumentCaptor<AiSampleLabelService.LabelBatch> captor = ArgumentCaptor.forClass(
                AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(captor.capture());
        AiSampleLabelService.SampleInput input = captor.getValue().samples().get(0);
        assertThat(input.sectorSeries()).isNotNull();
        assertThat(input.sectorSeries().symbol()).isEqualTo("BK0475");
        assertThat(input.sectorSeries().points())
                .extracting(KlinePointResponse::tradeDate)
                .containsExactly(
                        LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3),
                        LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 8));
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void reportsMissingSectorBenchmarkWithoutInventingSectorReturnInput() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample sample = sample(21L, "600519");
        sample.dataBatchId = 88L;
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        when(fixture.observationMapper.selectReadyIndustryMembershipByBatch(88L, "600519"))
                .thenReturn(membershipObservation(88L, "600519"));
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of());

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);

        ArgumentCaptor<AiSampleLabelService.LabelBatch> captor = ArgumentCaptor.forClass(
                AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(captor.capture());
        assertThat(captor.getValue().samples().get(0).sectorSeries()).isNull();
        assertThat(result.errors()).anySatisfy(message -> assertThat(message)
                .contains("600519")
                .contains("已有行业归属")
                .contains("行业基准行情不可用"));
    }

    @Test
    void rejectsMockSectorPayloadEvenWhenObservationClaimsReady() throws Exception {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample sample = sample(21L, "600519");
        sample.dataBatchId = 88L;
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        AiSourceObservation mockAnchor = sectorObservation(
                88L, "600519", "BK0475", LocalDateTime.of(2026, 7, 3, 16, 0),
                List.of(point(LocalDate.of(2026, 7, 3))), "MOCK");
        when(fixture.observationMapper.selectReadyIndustryMembershipByBatch(88L, "600519"))
                .thenReturn(membershipObservation(88L, "600519"));
        when(fixture.observationMapper.selectReadyIndustryBenchmarkByBatch(88L, "600519"))
                .thenReturn(mockAnchor);
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of());

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);

        ArgumentCaptor<AiSampleLabelService.LabelBatch> captor = ArgumentCaptor.forClass(
                AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(captor.capture());
        assertThat(captor.getValue().samples().get(0).sectorSeries()).isNull();
        assertThat(result.errors()).anySatisfy(message -> assertThat(message)
                .contains("行业基准证据来源、指纹或时点不可验证"));
    }

    @Test
    void attachesNormalizedPointInTimeIndustryArchiveBeforeObservationFallback() {
        Fixture fixture = fixtureWithIndustryArchive();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample sample = sample(21L, "600519");
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        when(fixture.universeItemMapper.selectReadyIndustryMembershipsForSamples(
                List.of(21L), verifiedAt)).thenReturn(List.of(industryMembership(21L)));
        when(fixture.industryBarMapper.selectCurrentSeries(
                List.of("801120.SI"), "SW2021", LocalDate.of(2026, 7, 3), tradeDate, verifiedAt))
                .thenReturn(List.of(
                        industryBar(LocalDate.of(2026, 7, 3), "industry-bar-1", "TUSHARE"),
                        industryBar(LocalDate.of(2026, 7, 8), "industry-bar-2", "TUSHARE")));
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of());

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);

        ArgumentCaptor<AiSampleLabelService.LabelBatch> captor = ArgumentCaptor.forClass(
                AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(captor.capture());
        AiSampleLabelService.SampleInput input = captor.getValue().samples().get(0);
        assertThat(input.sectorMembershipFingerprint()).isEqualTo("membership-archive-v1");
        assertThat(input.sectorSeries()).isNotNull();
        assertThat(input.sectorSeries().source()).isEqualTo("ARCHIVE_TUSHARE");
        assertThat(input.sectorSeries().points()).extracting(KlinePointResponse::tradeDate)
                .containsExactly(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 8));
        assertThat(result.errors()).isEmpty();
        verify(fixture.observationMapper, never())
                .selectReadyIndustryMembershipByBatch(any(), anyString());
    }

    @Test
    void reportsNormalizedMembershipWithMissingIndustryBarsWithoutInventingReturn() {
        Fixture fixture = fixtureWithIndustryArchive();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample sample = sample(21L, "600519");
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        when(fixture.universeItemMapper.selectReadyIndustryMembershipsForSamples(
                List.of(21L), verifiedAt)).thenReturn(List.of(industryMembership(21L)));
        when(fixture.industryBarMapper.selectCurrentSeries(
                List.of("801120.SI"), "SW2021", LocalDate.of(2026, 7, 3), tradeDate, verifiedAt))
                .thenReturn(List.of());
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of());

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.matureSampleLabels(
                tradeDate, verifiedAt);

        ArgumentCaptor<AiSampleLabelService.LabelBatch> captor = ArgumentCaptor.forClass(
                AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(captor.capture());
        AiSampleLabelService.SampleInput input = captor.getValue().samples().get(0);
        assertThat(input.sectorSeries()).isNull();
        assertThat(input.sectorMembershipFingerprint()).isEqualTo("membership-archive-v1");
        assertThat(result.errors()).anySatisfy(message -> assertThat(message)
                .contains("历史行业日线不可用"));
    }

    @Test
    void evaluatesPredictionsOnlyAgainstAlreadyMaturedLabels() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        AiPrediction prediction = prediction(31L, 21L, "600519");
        when(fixture.predictionMapper.selectUnevaluatedCandidates(
                tradeDate, "LABEL/1.1.0", AiPredictionEvaluationServiceImpl.VERSION, 2000))
                .thenReturn(List.of(prediction), List.of());
        AiSampleLabel label = new AiSampleLabel();
        label.id = 81L;
        label.sampleId = 21L;
        label.labelStatus = "MATURED";
        when(fixture.labelMapper.selectMaturedForSamples(List.of(21L), "LABEL/1.1.0"))
                .thenReturn(List.of(label));
        AiPredictionEvaluation evaluation = new AiPredictionEvaluation();
        evaluation.id = 82L;
        when(fixture.evaluationService.evaluateAndStore(any())).thenReturn(List.of(evaluation));

        AiLabelVerificationCoordinator.VerificationResult result = fixture.service.evaluatePredictions(
                tradeDate, tradeDate.atTime(16, 0));

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
        verify(fixture.labelService, never()).matureAndStore(any());
        verify(fixture.marketDataService, never()).klineAt(anyString(), anyString(), any(Integer.class), any());
    }

    @Test
    void onlyBuildsMissingHorizonsWhenSampleAlreadyHasAnImmutableLabel() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample sample = sample(21L, "600519");
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        AiSampleLabel existing = new AiSampleLabel();
        existing.sampleId = sample.id;
        existing.horizonTradingDays = 1;
        when(fixture.labelMapper.selectForSamplesAndVersion(
                List.of(sample.id), "LABEL/1.1.0")).thenReturn(List.of(existing));
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of());

        fixture.service.matureSampleLabels(tradeDate, verifiedAt);

        ArgumentCaptor<AiSampleLabelService.LabelBatch> batchCaptor =
                ArgumentCaptor.forClass(AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(batchCaptor.capture());
        assertThat(batchCaptor.getValue().horizons()).containsExactly(2, 3, 5);
    }

    @Test
    void scansPastUnavailableStocksInsteadOfRepeatingTheFirstPageForever() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample firstUnavailable = sample(21L, "300058");
        AiSample secondUnavailable = sample(22L, "300058");
        AiSample firstAvailable = sample(23L, "600519");
        AiSample secondAvailable = sample(24L, "000001");
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(8)))
                .thenReturn(List.of(firstUnavailable, secondUnavailable));
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                tradeDate, "LABEL/1.1.0", secondUnavailable.tradeDate,
                secondUnavailable.stockCode, secondUnavailable.id, 8))
                .thenReturn(List.of(firstAvailable, secondAvailable));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("300058", "day", 320, verifiedAt))
                .thenThrow(new IllegalStateException("K线暂不可用"));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.marketDataService.klineAt("000001", "day", 320, verifiedAt))
                .thenReturn(series("000001", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        AiSampleLabel firstLabel = new AiSampleLabel();
        firstLabel.id = 81L;
        firstLabel.sampleId = firstAvailable.id;
        firstLabel.labelStatus = "MATURED";
        AiSampleLabel secondLabel = new AiSampleLabel();
        secondLabel.id = 82L;
        secondLabel.sampleId = secondAvailable.id;
        secondLabel.labelStatus = "MATURED";
        when(fixture.labelService.matureAndStore(any()))
                .thenReturn(List.of(firstLabel, secondLabel));

        AiLabelVerificationCoordinator.VerificationResult result =
                fixture.service.matureSampleLabels(tradeDate, verifiedAt, 2);

        assertThat(result.processedCount()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.errors()).singleElement().asString()
                .contains("300058:")
                .contains("K线暂不可用");
        ArgumentCaptor<AiSampleLabelService.LabelBatch> batchCaptor =
                ArgumentCaptor.forClass(AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(batchCaptor.capture());
        assertThat(batchCaptor.getValue().samples())
                .extracting(AiSampleLabelService.SampleInput::stockCode)
                .containsExactly("600519", "000001");
    }

    @Test
    void stateRevisionCandidatesRebuildAllHorizonsEvenWhenOldLabelsAreComplete() {
        AiSecurityDailyStateMapper stateMapper = mock(AiSecurityDailyStateMapper.class);
        Fixture fixture = fixture(stateMapper);
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        AiSample sample = sample(21L, "600519");
        sample.stateRefreshRequired = 1;
        when(fixture.sampleMapper.selectLabelCandidateScanPage(
                eq(tradeDate), anyString(), isNull(), isNull(), isNull(), eq(2000)))
                .thenReturn(List.of(sample));
        when(fixture.marketDataService.klineAt("000300.SH", "day", 320, verifiedAt))
                .thenReturn(series("000300.SH", verifiedAt));
        when(fixture.marketDataService.klineAt("600519", "day", 320, verifiedAt))
                .thenReturn(series("600519", verifiedAt));
        when(fixture.calendarMapper.selectByDates(anyString(), anyString(), anyList()))
                .thenReturn(List.of(calendar(91L, LocalDate.of(2026, 7, 8))));
        when(stateMapper.selectCurrentForStocksBetween(anyList(), any(), any())).thenReturn(List.of());
        when(fixture.labelMapper.selectForSamplesAndVersion(List.of(sample.id), "LABEL/1.1.0"))
                .thenReturn(List.of(label(sample.id, 1), label(sample.id, 2), label(sample.id, 3), label(sample.id, 5)));
        when(fixture.labelService.matureAndStore(any())).thenReturn(List.of());

        fixture.service.matureSampleLabels(tradeDate, verifiedAt);

        ArgumentCaptor<AiSampleLabelService.LabelBatch> batchCaptor = ArgumentCaptor.forClass(
                AiSampleLabelService.LabelBatch.class);
        verify(fixture.labelService).matureAndStore(batchCaptor.capture());
        assertThat(batchCaptor.getValue().horizons()).containsExactly(1, 2, 3, 5);
    }

    private static Fixture fixture() {
        return fixture(null);
    }

    private static Fixture fixture(AiSecurityDailyStateMapper securityDailyStateMapper) {
        return fixture(securityDailyStateMapper, null, null);
    }

    private static Fixture fixtureWithIndustryArchive() {
        return fixture(null, mock(AiResearchUniverseItemMapper.class),
                mock(AiIndustryDailyBarMapper.class));
    }

    private static Fixture fixture(
            AiSecurityDailyStateMapper securityDailyStateMapper,
            AiResearchUniverseItemMapper universeItemMapper,
            AiIndustryDailyBarMapper industryBarMapper
    ) {
        AiPredictionMapper predictionMapper = mock(AiPredictionMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AiSourceObservationMapper observationMapper = mock(AiSourceObservationMapper.class);
        AiTradingCalendarMapper calendarMapper = mock(AiTradingCalendarMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        HistoricalMarketDataProvider historicalProvider = mock(HistoricalMarketDataProvider.class);
        when(historicalProvider.providerCode()).thenReturn("SINA_TENCENT");
        AiSampleLabelService labelService = mock(AiSampleLabelService.class);
        AiPredictionEvaluationService evaluationService = mock(AiPredictionEvaluationService.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ZoneId zone = ZoneId.systemDefault();
        Clock clock = Clock.fixed(EVIDENCE_VERIFIED_AT.atZone(zone).toInstant(), zone);
        AiLabelVerificationCoordinator service = new AiLabelVerificationCoordinatorImpl(
                predictionMapper, sampleMapper, labelMapper, observationMapper, securityDailyStateMapper,
                universeItemMapper, industryBarMapper, calendarMapper, marketDataService,
                List.of(historicalProvider), labelService, evaluationService, objectMapper, clock);
        return new Fixture(predictionMapper, sampleMapper, labelMapper, observationMapper, calendarMapper,
                universeItemMapper, industryBarMapper, marketDataService, historicalProvider,
                labelService, evaluationService, service);
    }

    private static AiSample sample(Long id, String code) {
        AiSample sample = new AiSample();
        sample.id = id;
        sample.stockCode = code;
        sample.tradeDate = LocalDate.of(2026, 7, 3);
        sample.tradableStatus = "TRADABLE";
        sample.sourceFingerprint = "sample-" + id;
        return sample;
    }

    private static AiPrediction prediction(Long id, Long sampleId, String code) {
        AiPrediction prediction = new AiPrediction();
        prediction.id = id;
        prediction.sampleId = sampleId;
        prediction.stockCode = code;
        prediction.horizonDays = 3;
        prediction.action = "BUY";
        prediction.actionBucket = "RECOMMEND";
        prediction.targetDirection = "UP";
        prediction.expectedReturn = new BigDecimal("0.02");
        prediction.probabilityUp = new BigDecimal("0.70");
        prediction.probabilityDown = new BigDecimal("0.30");
        prediction.inputFingerprint = "prediction-" + id;
        return prediction;
    }

    private static AiSampleLabel label(Long sampleId, int horizon) {
        AiSampleLabel label = new AiSampleLabel();
        label.sampleId = sampleId;
        label.horizonTradingDays = horizon;
        return label;
    }

    private static KlineSeriesSnapshot series(String symbol, LocalDateTime asOf) {
        return KlineSeriesSnapshot.create(symbol, "day", "NONE", "EASTMONEY", asOf,
                asOf.plusSeconds(3), List.of(
                        point(LocalDate.of(2026, 7, 6)),
                        point(LocalDate.of(2026, 7, 7)),
                        point(LocalDate.of(2026, 7, 8))));
    }

    private static AiTradingCalendar calendar(Long id, LocalDate date) {
        AiTradingCalendar value = new AiTradingCalendar();
        value.id = id;
        value.tradeDate = date;
        value.isTradeDay = 1;
        value.sessionCloseTime = LocalTime.of(15, 0);
        value.calendarVersion = "CN_A_CALENDAR/1.0.0";
        value.sourceFingerprint = "calendar-" + date;
        return value;
    }

    private static KlinePointResponse point(LocalDate date) {
        return new KlinePointResponse(date, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, 1000L, new BigDecimal("10000"));
    }

    private static AiSourceObservation snapshotObservation(Long batchId, String stockCode, LocalDate tradeDate) {
        AiSourceObservation observation = new AiSourceObservation();
        observation.id = batchId;
        observation.dataBatchId = batchId;
        observation.stockCode = stockCode;
        observation.sourceType = "STOCK_DAILY_SNAPSHOT";
        observation.providerCode = "EASTMONEY";
        observation.qualityStatus = "READY";
        observation.eventTime = tradeDate.atTime(15, 0);
        observation.asOfTime = tradeDate.atTime(16, 0);
        observation.fetchedAt = tradeDate.atTime(15, 30);
        observation.payloadJson = "{"
                + "\"quote\":null,"
                + "\"finance\":null,"
                + "\"intraday\":[],"
                + "\"kline\":["
                + klineJson(tradeDate.minusDays(1)) + ","
                + klineJson(tradeDate)
                + "],"
                + "\"aiAdvice\":null,"
                + "\"aiScore\":null"
                + "}";
        return observation;
    }

    private static AiSourceObservation membershipObservation(Long batchId, String stockCode) {
        AiSourceObservation observation = new AiSourceObservation();
        observation.id = batchId;
        observation.dataBatchId = batchId;
        observation.stockCode = stockCode;
        observation.sourceType = "INDUSTRY_MEMBERSHIP";
        observation.qualityStatus = "READY";
        observation.asOfTime = LocalDateTime.of(2026, 7, 3, 16, 0);
        observation.sourceFingerprint = "membership-" + batchId + "-" + stockCode;
        return observation;
    }

    private static AiResearchUniverseItem industryMembership(Long sampleId) {
        AiResearchUniverseItem item = new AiResearchUniverseItem();
        item.sampleId = sampleId;
        item.stockCode = "600519";
        item.industryCode = "801120.SI";
        item.industryName = "食品饮料";
        item.industryStandard = "SW2021";
        item.sourceFingerprint = "membership-archive-v1";
        return item;
    }

    private static AiIndustryDailyBar industryBar(LocalDate tradeDate, String fingerprint, String source) {
        AiIndustryDailyBar bar = new AiIndustryDailyBar();
        bar.industryCode = "801120.SI";
        bar.industryName = "食品饮料";
        bar.classificationStandard = "SW2021";
        bar.tradeDate = tradeDate;
        bar.openPrice = new BigDecimal("100");
        bar.highPrice = new BigDecimal("102");
        bar.lowPrice = new BigDecimal("99");
        bar.closePrice = new BigDecimal("101");
        bar.volume = new BigDecimal("1000");
        bar.amount = new BigDecimal("100000");
        bar.sourceName = source;
        bar.qualityStatus = "READY";
        bar.sourceFingerprint = fingerprint;
        bar.observedAt = tradeDate.atTime(15, 30);
        return bar;
    }

    private static AiSourceObservation sectorObservation(
            Long id,
            String stockCode,
            String sectorCode,
            LocalDateTime asOfTime,
            List<KlinePointResponse> points
    ) throws Exception {
        return sectorObservation(id, stockCode, sectorCode, asOfTime, points, "EASTMONEY");
    }

    private static AiSourceObservation sectorObservation(
            Long id,
            String stockCode,
            String sectorCode,
            LocalDateTime asOfTime,
            List<KlinePointResponse> points,
            String source
    ) throws Exception {
        KlineSeriesSnapshot series = KlineSeriesSnapshot.create(
                sectorCode, "day", "NONE", source, asOfTime, asOfTime.minusMinutes(1), points);
        AiSourceObservation observation = new AiSourceObservation();
        observation.id = id;
        observation.dataBatchId = id;
        observation.stockCode = stockCode;
        observation.sourceType = "INDUSTRY_BENCHMARK";
        observation.providerCode = "EASTMONEY";
        observation.qualityStatus = "READY";
        observation.asOfTime = asOfTime;
        observation.availableAt = asOfTime;
        observation.fetchedAt = asOfTime.minusMinutes(1);
        observation.payloadJson = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsString(series);
        return observation;
    }

    private static String klineJson(LocalDate date) {
        return "{"
                + "\"tradeDate\":\"" + date + "\","
                + "\"open\":10,"
                + "\"close\":10,"
                + "\"high\":10,"
                + "\"low\":10,"
                + "\"volume\":1000,"
                + "\"amount\":10000"
                + "}";
    }

    private record Fixture(
            AiPredictionMapper predictionMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiSourceObservationMapper observationMapper,
            AiTradingCalendarMapper calendarMapper,
            AiResearchUniverseItemMapper universeItemMapper,
            AiIndustryDailyBarMapper industryBarMapper,
            MarketDataService marketDataService,
            HistoricalMarketDataProvider historicalProvider,
            AiSampleLabelService labelService,
            AiPredictionEvaluationService evaluationService,
            AiLabelVerificationCoordinator service
    ) {
    }
}
