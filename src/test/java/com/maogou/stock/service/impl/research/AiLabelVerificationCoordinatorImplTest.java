package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import com.maogou.stock.service.research.AiPredictionEvaluationService;
import com.maogou.stock.service.research.AiSampleLabelService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLabelVerificationCoordinatorImplTest {

    @Test
    void maturesMarketLabelsOnceAndKeepsPerStockFailuresAsWarnings() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);
        when(fixture.sampleMapper.selectList(any())).thenReturn(List.of(
                sample(21L, "600519"), sample(22L, "300058")));
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
        assertThat(result.errors()).containsExactly("300058: K线暂不可用");
        verify(fixture.evaluationService, never()).evaluateAndStore(any());
    }

    @Test
    void evaluatesPredictionsOnlyAgainstAlreadyMaturedLabels() {
        Fixture fixture = fixture();
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        AiPrediction prediction = prediction(31L, 21L, "600519");
        when(fixture.predictionMapper.selectUnevaluatedCandidates(
                tradeDate, AiPredictionEvaluationServiceImpl.VERSION, 2000))
                .thenReturn(List.of(prediction));
        AiSampleLabel label = new AiSampleLabel();
        label.id = 81L;
        label.sampleId = 21L;
        label.labelStatus = "MATURED";
        when(fixture.labelMapper.selectList(any())).thenReturn(List.of(label));
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

    private static Fixture fixture() {
        AiPredictionMapper predictionMapper = mock(AiPredictionMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AiTradingCalendarMapper calendarMapper = mock(AiTradingCalendarMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleLabelService labelService = mock(AiSampleLabelService.class);
        AiPredictionEvaluationService evaluationService = mock(AiPredictionEvaluationService.class);
        AiLabelVerificationCoordinator service = new AiLabelVerificationCoordinatorImpl(
                predictionMapper, sampleMapper, labelMapper, calendarMapper, marketDataService,
                labelService, evaluationService);
        return new Fixture(predictionMapper, sampleMapper, labelMapper, calendarMapper,
                marketDataService, labelService, evaluationService, service);
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

    private static KlineSeriesSnapshot series(String symbol, LocalDateTime asOf) {
        return KlineSeriesSnapshot.create(symbol, "day", "NONE", "EASTMONEY", asOf,
                asOf.minusSeconds(1), List.of(
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

    private record Fixture(
            AiPredictionMapper predictionMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiTradingCalendarMapper calendarMapper,
            MarketDataService marketDataService,
            AiSampleLabelService labelService,
            AiPredictionEvaluationService evaluationService,
            AiLabelVerificationCoordinator service
    ) {
    }
}
