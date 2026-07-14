package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisDecision;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.mapper.AiAnalysisDecisionMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.service.research.AiDailyDecisionProjector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiDailyDecisionProjectorImplTest {

    @Test
    void unavailableV2PredictionCannotBeReclassifiedAsOrdinaryWatch() {
        AiSample sample = new AiSample();
        sample.qualityStatus = "PARTIAL";
        sample.tradableStatus = "TRADABLE";
        AiPrediction prediction = new AiPrediction();
        prediction.action = "UNAVAILABLE";
        prediction.score = new BigDecimal("42");
        AiDailyInsightScoring.Decision ordinaryWatch = new AiDailyInsightScoring.Decision(
                "WATCH", "WATCH", new BigDecimal("42"), "AI_DECISION_MISSING",
                BigDecimal.ZERO, 0, "LOW_SAMPLE");

        AiDailyInsightScoring.Decision result = AiDailyDecisionProjectorImpl.applyAvailabilityGate(
                sample, prediction, ordinaryWatch);

        assertThat(result.finalAction()).isEqualTo("UNAVAILABLE");
        assertThat(result.confidenceLevel()).isEqualTo("DATA_UNAVAILABLE");
    }

    @Test
    void projectsV2PredictionLabelsAndStructuredAiDecisionIntoDailyInsight() {
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiPredictionMapper predictionMapper = mock(AiPredictionMapper.class);
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AiFactorValueMapper factorMapper = mock(AiFactorValueMapper.class);
        AiFactorPerformanceMapper performanceMapper = mock(AiFactorPerformanceMapper.class);
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        AiAnalysisDecisionMapper decisionMapper = mock(AiAnalysisDecisionMapper.class);
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime now = tradeDate.atTime(16, 10);

        AiSample sample = new AiSample();
        sample.id = 21L;
        sample.userId = 5L;
        sample.stockCode = "600519";
        sample.stockName = "贵州茅台";
        sample.tradeDate = tradeDate;
        sample.samplePhase = "AFTER_CLOSE";
        sample.asOfTime = tradeDate.atTime(16, 0);
        sample.universeCode = "WATCHLIST";
        sample.dataQualityScore = new BigDecimal("86");
        sample.qualityStatus = "READY";
        sample.featureSnapshot = "{\"kline\":[{\"tradeDate\":\"2026-07-10\"}]}";
        when(sampleMapper.selectList(any())).thenReturn(List.of(sample));

        AiPrediction prediction = new AiPrediction();
        prediction.id = 31L;
        prediction.sampleId = 21L;
        prediction.userId = 5L;
        prediction.stockCode = "600519";
        prediction.tradeDate = tradeDate;
        prediction.score = new BigDecimal("78");
        prediction.riskScore = new BigDecimal("32");
        prediction.calibratedConfidence = new BigDecimal("82");
        prediction.targetDirection = "UP";
        prediction.horizonDays = 3;
        prediction.inferenceMode = "RULE_BASELINE";
        prediction.reasonJson = "{\"factors\":[{\"factorCode\":\"MOMENTUM_RETURN_5D\",\"contribution\":1.2}]}";
        prediction.predictedAt = now.minusMinutes(5);
        AiPrediction challenger = new AiPrediction();
        challenger.id = 99L;
        challenger.sampleId = 21L;
        challenger.userId = 5L;
        challenger.stockCode = "600519";
        challenger.tradeDate = tradeDate;
        challenger.horizonDays = 3;
        challenger.inferenceMode = "CHALLENGER_SHADOW";
        challenger.score = new BigDecimal("99");
        challenger.riskScore = BigDecimal.ONE;
        challenger.targetDirection = "UP";
        challenger.reasonJson = "{}";
        challenger.predictedAt = now.minusMinutes(1);
        when(predictionMapper.selectList(any())).thenReturn(List.of(challenger, prediction));

        List<AiSampleLabel> labels = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            AiSampleLabel label = new AiSampleLabel();
            label.stockCode = "600519";
            label.netReturn = index < 8 ? new BigDecimal("0.03") : new BigDecimal("-0.03");
            label.labelStatus = "MATURED";
            labels.add(label);
        }
        when(labelMapper.selectList(any())).thenReturn(labels);
        AiFactorValue oldFactor = new AiFactorValue();
        oldFactor.sampleId = 21L;
        oldFactor.factorCode = "MOMENTUM_RETURN_5D";
        oldFactor.factorVersion = "1.0.0";
        oldFactor.evidence = "OLD_FACTOR_EVIDENCE";
        AiFactorValue currentFactor = new AiFactorValue();
        currentFactor.sampleId = 21L;
        currentFactor.factorCode = "MOMENTUM_RETURN_5D";
        currentFactor.factorVersion = "2.0.0";
        currentFactor.evidence = "CURRENT_FACTOR_EVIDENCE";
        when(factorMapper.selectList(any())).thenReturn(List.of(oldFactor, currentFactor));
        when(performanceMapper.selectList(any())).thenReturn(List.of());

        AiAnalysisReport report = new AiAnalysisReport();
        report.id = 41L;
        report.userId = 5L;
        report.stockCode = "600519";
        report.stockName = "贵州茅台";
        report.reportDate = tradeDate;
        report.generatedAt = now.minusMinutes(2);
        when(reportMapper.selectList(any())).thenReturn(List.of(report));
        AiAnalysisDecision decision = new AiAnalysisDecision();
        decision.reportId = 41L;
        decision.decision = "BUY";
        decision.confidence = new BigDecimal("0.82");
        decision.targetDirection = "UP";
        decision.riskLevel = "MEDIUM";
        when(decisionMapper.selectList(any())).thenReturn(List.of(decision));

        AiDailyDecisionProjector projector = new AiDailyDecisionProjectorImpl(
                sampleMapper, predictionMapper, labelMapper, factorMapper, performanceMapper,
                reportMapper, decisionMapper, new ObjectMapper().findAndRegisterModules());

        List<AiDailyInsightItem> items = projector.project(5L, tradeDate, 88L, now);

        assertThat(items).hasSize(1);
        AiDailyInsightItem item = items.get(0);
        assertThat(item.sampleId).isEqualTo(21L);
        assertThat(item.predictionId).isEqualTo(31L);
        assertThat(item.reportId).isEqualTo(41L);
        assertThat(item.actionBucket).isEqualTo("RECOMMEND");
        assertThat(item.historicalSampleCount).isEqualTo(12);
        assertThat(item.triggerFactorsJson).contains("MOMENTUM_RETURN_5D");
        assertThat(item.triggerFactorsJson).contains("CURRENT_FACTOR_EVIDENCE");
        assertThat(item.triggerFactorsJson).doesNotContain("OLD_FACTOR_EVIDENCE");
        assertThat(item.freshnessStatus).isEqualTo("FRESH");
    }
}
