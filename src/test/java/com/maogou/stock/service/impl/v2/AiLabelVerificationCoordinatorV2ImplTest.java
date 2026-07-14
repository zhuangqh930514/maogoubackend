package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.research.AiSampleLabelService;
import com.maogou.stock.service.research.AiPredictionEvaluationService;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLabelVerificationCoordinatorImplTest {

    @Test
    void verifiesMaturedPredictionsAndKeepsPerStockFailuresAsWarnings() {
        AiPredictionMapper predictionMapper = mock(AiPredictionMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiTradingCalendarMapper calendarMapper = mock(AiTradingCalendarMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleLabelService labelService = mock(AiSampleLabelService.class);
        AiPredictionEvaluationService evaluationService = mock(AiPredictionEvaluationService.class);
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);

        AiPrediction maotai = prediction(31L, 21L, "600519");
        AiPrediction cursor = prediction(32L, 22L, "300058");
        when(predictionMapper.selectUnverifiedCandidates(5L, tradeDate, "LABEL/1.0.0", 300))
                .thenReturn(List.of(maotai, cursor));
        when(sampleMapper.selectBatchIds(anyList())).thenReturn(List.of(
                sample(21L, "600519"), sample(22L, "300058")));

        KlineSeriesSnapshot benchmark = KlineSeriesSnapshot.create(
                "000300.SH", "day", "NONE", "EASTMONEY", verifiedAt, verifiedAt.minusSeconds(1),
                List.of(
                        point(LocalDate.of(2026, 7, 6)),
                        point(LocalDate.of(2026, 7, 7)),
                        point(LocalDate.of(2026, 7, 8))));
        KlineSeriesSnapshot stock = KlineSeriesSnapshot.create(
                "600519", "day", "NONE", "EASTMONEY", verifiedAt, verifiedAt.minusSeconds(1),
                List.of(
                        point(LocalDate.of(2026, 7, 6)),
                        point(LocalDate.of(2026, 7, 7)),
                        point(LocalDate.of(2026, 7, 8))));
        when(marketDataService.klineAt("000300.SH", "day", 240, verifiedAt)).thenReturn(benchmark);
        when(marketDataService.klineAt("600519", "day", 240, verifiedAt)).thenReturn(stock);
        when(marketDataService.klineAt("300058", "day", 240, verifiedAt))
                .thenThrow(new IllegalStateException("K线暂不可用"));
        AiTradingCalendar day = calendar(91L, LocalDate.of(2026, 7, 6));
        when(calendarMapper.selectByDates(anyString(), anyString(), anyList())).thenReturn(List.of(day));
        AiSampleLabel label = new AiSampleLabel();
        label.id = 81L;
        label.sampleId = 21L;
        label.horizonTradingDays = 3;
        AiPredictionEvaluation evaluation = new AiPredictionEvaluation();
        evaluation.id = 82L;
        when(labelService.matureAndStore(any())).thenReturn(List.of(label));
        when(evaluationService.evaluateAndStore(any())).thenReturn(List.of(evaluation));

        AiLabelVerificationCoordinator service = new AiLabelVerificationCoordinatorImpl(
                predictionMapper, sampleMapper, calendarMapper, marketDataService,
                labelService, evaluationService);

        AiLabelVerificationCoordinator.VerificationResult result =
                service.verifyMatured(5L, tradeDate, verifiedAt);

        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.errors()).containsExactly("300058: K线暂不可用");
        assertThat(result.outputFingerprint()).isNotBlank();
        verify(marketDataService).klineAt("000300.SH", "day", 240, verifiedAt);
        verify(labelService).matureAndStore(any());
        verify(evaluationService).evaluateAndStore(any());
    }

    @Test
    void returnsAnIdempotentEmptyResultWhenNoPredictionHasMatured() {
        AiPredictionMapper predictionMapper = mock(AiPredictionMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiTradingCalendarMapper calendarMapper = mock(AiTradingCalendarMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleLabelService labelService = mock(AiSampleLabelService.class);
        AiPredictionEvaluationService evaluationService = mock(AiPredictionEvaluationService.class);
        when(predictionMapper.selectUnverifiedCandidates(any(), any(), anyString(), anyInt()))
                .thenReturn(List.of());
        AiLabelVerificationCoordinator service = new AiLabelVerificationCoordinatorImpl(
                predictionMapper, sampleMapper, calendarMapper, marketDataService,
                labelService, evaluationService);

        AiLabelVerificationCoordinator.VerificationResult result = service.verifyMatured(
                5L, LocalDate.of(2026, 7, 10), LocalDateTime.of(2026, 7, 10, 16, 0));

        assertThat(result.processedCount()).isZero();
        assertThat(result.outputFingerprint()).isNotBlank();
        verify(marketDataService, never()).klineAt(anyString(), anyString(), anyInt(), any());
        verify(labelService, never()).matureAndStore(any());
        verify(evaluationService, never()).evaluateAndStore(any());
    }

    private static AiPrediction prediction(Long id, Long sampleId, String code) {
        AiPrediction value = new AiPrediction();
        value.id = id;
        value.userId = 5L;
        value.sampleId = sampleId;
        value.stockCode = code;
        value.tradeDate = LocalDate.of(2026, 7, 3);
        value.samplePhase = "AFTER_CLOSE";
        value.horizonDays = 3;
        value.inputFingerprint = "prediction-" + id;
        return value;
    }

    private static AiSample sample(Long id, String code) {
        AiSample value = new AiSample();
        value.id = id;
        value.userId = 5L;
        value.stockCode = code;
        value.tradeDate = LocalDate.of(2026, 7, 3);
        value.samplePhase = "AFTER_CLOSE";
        value.sourceFingerprint = "sample-" + id;
        return value;
    }

    private static AiTradingCalendar calendar(Long id, LocalDate date) {
        AiTradingCalendar value = new AiTradingCalendar();
        value.id = id;
        value.marketCode = "CN_A_SHARE";
        value.tradeDate = date;
        value.calendarVersion = "CN_A_SHARE_V1";
        value.isTradeDay = 1;
        value.sessionOpenTime = LocalTime.of(9, 30);
        value.sessionCloseTime = LocalTime.of(15, 0);
        value.sourceFingerprint = "calendar-" + date;
        return value;
    }

    private static KlinePointResponse point(LocalDate date) {
        return new KlinePointResponse(
                date, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, 1000L, new BigDecimal("10000"));
    }
}
