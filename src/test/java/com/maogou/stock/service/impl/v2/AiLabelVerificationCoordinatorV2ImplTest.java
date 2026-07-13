package com.maogou.stock.service.impl.v2;

import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.domain.entity.v2.AiTradingCalendar;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.mapper.v2.AiPredictionV2Mapper;
import com.maogou.stock.mapper.v2.AiSampleV2Mapper;
import com.maogou.stock.mapper.v2.AiTradingCalendarMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.v2.AiLabelServiceV2;
import com.maogou.stock.service.v2.AiLabelVerificationCoordinatorV2;
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

class AiLabelVerificationCoordinatorV2ImplTest {

    @Test
    void verifiesMaturedPredictionsAndKeepsPerStockFailuresAsWarnings() {
        AiPredictionV2Mapper predictionMapper = mock(AiPredictionV2Mapper.class);
        AiSampleV2Mapper sampleMapper = mock(AiSampleV2Mapper.class);
        AiTradingCalendarMapper calendarMapper = mock(AiTradingCalendarMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiLabelServiceV2 labelService = mock(AiLabelServiceV2.class);
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime verifiedAt = tradeDate.atTime(16, 0);

        AiPredictionV2 maotai = prediction(31L, 21L, "600519");
        AiPredictionV2 cursor = prediction(32L, 22L, "300058");
        when(predictionMapper.selectUnverifiedCandidates(5L, tradeDate, "LABEL_V2.2", 300))
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
        AiLabelV2 label = new AiLabelV2();
        label.id = 81L;
        label.predictionId = 31L;
        when(labelService.verifyAndStore(any())).thenReturn(List.of(label));

        AiLabelVerificationCoordinatorV2 service = new AiLabelVerificationCoordinatorV2Impl(
                predictionMapper, sampleMapper, calendarMapper, marketDataService, labelService);

        AiLabelVerificationCoordinatorV2.VerificationResult result =
                service.verifyMatured(5L, tradeDate, verifiedAt);

        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.errors()).containsExactly("300058: K线暂不可用");
        assertThat(result.outputFingerprint()).isNotBlank();
        verify(marketDataService).klineAt("000300.SH", "day", 240, verifiedAt);
        verify(labelService).verifyAndStore(any());
    }

    @Test
    void returnsAnIdempotentEmptyResultWhenNoPredictionHasMatured() {
        AiPredictionV2Mapper predictionMapper = mock(AiPredictionV2Mapper.class);
        AiSampleV2Mapper sampleMapper = mock(AiSampleV2Mapper.class);
        AiTradingCalendarMapper calendarMapper = mock(AiTradingCalendarMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiLabelServiceV2 labelService = mock(AiLabelServiceV2.class);
        when(predictionMapper.selectUnverifiedCandidates(any(), any(), anyString(), anyInt()))
                .thenReturn(List.of());
        AiLabelVerificationCoordinatorV2 service = new AiLabelVerificationCoordinatorV2Impl(
                predictionMapper, sampleMapper, calendarMapper, marketDataService, labelService);

        AiLabelVerificationCoordinatorV2.VerificationResult result = service.verifyMatured(
                5L, LocalDate.of(2026, 7, 10), LocalDateTime.of(2026, 7, 10, 16, 0));

        assertThat(result.processedCount()).isZero();
        assertThat(result.outputFingerprint()).isNotBlank();
        verify(marketDataService, never()).klineAt(anyString(), anyString(), anyInt(), any());
        verify(labelService, never()).verifyAndStore(any());
    }

    private static AiPredictionV2 prediction(Long id, Long sampleId, String code) {
        AiPredictionV2 value = new AiPredictionV2();
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

    private static AiSampleV2 sample(Long id, String code) {
        AiSampleV2 value = new AiSampleV2();
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
