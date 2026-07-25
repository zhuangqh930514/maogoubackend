package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.research.AiDailyReportGenerationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiDailyReportGenerationServiceImplTest {

    private static final Long USER_ID = 5L;
    private static final Long BATCH_ID = 71L;
    private static final Long RELEASE_ID = 91L;
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 24);

    @Test
    void prioritizesHoldingsRiskSignalsAndTopCandidatesAndDoesNotAbortOnOneModelFailure() {
        WatchStockMapper watchMapper = mock(WatchStockMapper.class);
        TradeRecordMapper tradeMapper = mock(TradeRecordMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiPredictionMapper predictionMapper = mock(AiPredictionMapper.class);
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        AiAnalysisService analysisService = mock(AiAnalysisService.class);
        AiDailyReportGenerationService service = new AiDailyReportGenerationServiceImpl(
                watchMapper, tradeMapper, sampleMapper, predictionMapper, reportMapper, analysisService);

        List<String> codes = List.of("000001", "000002", "000003", "000004", "000005", "000006", "000007");
        when(watchMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(watches(codes));
        when(tradeMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(holding("000006")));
        List<AiSample> samples = samples(codes);
        when(sampleMapper.selectLatestForDecision(BATCH_ID, TRADE_DATE, codes)).thenReturn(samples);
        when(predictionMapper.selectForDailyDecision(anyList(), eq(RELEASE_ID))).thenReturn(predictions(samples));

        AiAnalysisReport reusable = report("000001", 1L);
        when(reportMapper.selectLatestSuccessfulForDailyDecision(USER_ID, TRADE_DATE, List.of(
                "000006", "000007", "000001", "000002", "000003", "000004", "000005")))
                .thenReturn(List.of(reusable));
        when(analysisService.analyzeStockForFormalSample(anyString(), eq(false), eq(null), eq(null), eq(TRADE_DATE),
                org.mockito.ArgumentMatchers.anyLong(), eq(RELEASE_ID)))
                .thenReturn(response("SUCCESS", null));
        when(analysisService.analyzeStockForFormalSample(eq("000007"), eq(false), eq(null), eq(null), eq(TRADE_DATE),
                org.mockito.ArgumentMatchers.anyLong(), eq(RELEASE_ID)))
                .thenReturn(response("FAILED", "模型接口触发限流，系统将在约 30 秒后自动重试。模型=qwen"));

        AiDailyReportGenerationService.GenerationResult result = service.generate(
                new AiDailyReportGenerationService.GenerationRequest(USER_ID, TRADE_DATE, BATCH_ID, RELEASE_ID));

        assertThat(result.eligibleCount()).isEqualTo(7);
        assertThat(result.selectedCount()).isEqualTo(7);
        assertThat(result.reusedCount()).isEqualTo(1);
        assertThat(result.generatedCount()).isEqualTo(5);
        assertThat(result.failed()).singleElement().satisfies(issue -> {
            assertThat(issue.stockCode()).isEqualTo("000007");
            assertThat(issue.reason()).contains("步骤=GENERATE_STOCK_REPORTS", "数据提供方=本地/第三方大模型", "限流");
        });
        assertThat(result.skipped()).isEmpty();
        verify(analysisService, times(1)).analyzeStockForFormalSample(eq("000006"), eq(false), eq(null), eq(null),
                eq(TRADE_DATE), eq(6L), eq(RELEASE_ID));
        verify(analysisService, times(1)).analyzeStockForFormalSample(eq("000002"), eq(false), eq(null), eq(null),
                eq(TRADE_DATE), eq(2L), eq(RELEASE_ID));
    }

    private static List<WatchStock> watches(List<String> codes) {
        List<WatchStock> result = new ArrayList<>();
        for (String code : codes) {
            WatchStock stock = new WatchStock();
            stock.userId = USER_ID;
            stock.stockCode = code;
            stock.stockName = "股票" + code;
            stock.priority = result.size();
            result.add(stock);
        }
        return result;
    }

    private static TradeRecord holding(String code) {
        TradeRecord record = new TradeRecord();
        record.userId = USER_ID;
        record.stockCode = code;
        record.side = TradeSide.BUY;
        record.quantity = 100;
        record.tradedAt = TRADE_DATE.atTime(10, 0);
        return record;
    }

    private static List<AiSample> samples(List<String> codes) {
        List<AiSample> result = new ArrayList<>();
        for (int index = 0; index < codes.size(); index++) {
            AiSample sample = new AiSample();
            sample.id = (long) index + 1;
            sample.dataBatchId = BATCH_ID;
            sample.stockCode = codes.get(index);
            sample.stockName = "股票" + codes.get(index);
            sample.tradeDate = TRADE_DATE;
            sample.asOfTime = TRADE_DATE.atTime(15, 5);
            sample.qualityStatus = "READY";
            sample.tradableStatus = "TRADABLE";
            sample.dataQualityScore = new BigDecimal("95");
            result.add(sample);
        }
        return result;
    }

    private static List<AiPrediction> predictions(List<AiSample> samples) {
        List<AiPrediction> result = new ArrayList<>();
        long id = 100L;
        for (AiSample sample : samples) {
            for (int horizon = 1; horizon <= 3; horizon++) {
                AiPrediction prediction = new AiPrediction();
                prediction.id = id++;
                prediction.sampleId = sample.id;
                prediction.strategyReleaseId = RELEASE_ID;
                prediction.stockCode = sample.stockCode;
                prediction.tradeDate = TRADE_DATE;
                prediction.horizonDays = horizon;
                prediction.predictedAt = LocalDateTime.of(2026, 7, 24, 15, 10);
                prediction.score = new BigDecimal(String.valueOf(100 - sample.id));
                prediction.riskScore = new BigDecimal("35");
                prediction.action = "000007".equals(sample.stockCode) ? "SELL" : "BUY";
                result.add(prediction);
            }
        }
        return result;
    }

    private static AiAnalysisReport report(String code, Long sampleId) {
        AiAnalysisReport report = new AiAnalysisReport();
        report.userId = USER_ID;
        report.stockCode = code;
        report.sampleId = sampleId;
        report.strategyReleaseId = RELEASE_ID;
        report.reportDate = TRADE_DATE;
        report.reportVersion = 1;
        report.status = AnalysisStatus.SUCCESS;
        return report;
    }

    private static AiAnalysisReportResponse response(String status, String errorMessage) {
        return new AiAnalysisReportResponse(
                1L, "测试股票", "000001", 80, "观察", LocalDateTime.now(),
                "", "", "", "", List.of(), "", "qwen", status, errorMessage,
                1L, RELEASE_ID, 1, null, new BigDecimal("95"), new BigDecimal("0.80"), null, null);
    }
}
