package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisDecision;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.mapper.AiAnalysisDecisionMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.v2.AiFactorPerformanceV2Mapper;
import com.maogou.stock.mapper.v2.AiFactorValueV2Mapper;
import com.maogou.stock.mapper.v2.AiLabelV2Mapper;
import com.maogou.stock.mapper.v2.AiPredictionV2Mapper;
import com.maogou.stock.mapper.v2.AiSampleV2Mapper;
import com.maogou.stock.service.v2.AiDailyInsightV2Projector;
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

class AiDailyInsightV2ProjectorImplTest {

    @Test
    void unavailableV2PredictionCannotBeReclassifiedAsOrdinaryWatch() {
        AiSampleV2 sample = new AiSampleV2();
        sample.qualityStatus = "PARTIAL";
        sample.tradableStatus = "TRADABLE";
        AiPredictionV2 prediction = new AiPredictionV2();
        prediction.action = "UNAVAILABLE";
        prediction.score = new BigDecimal("42");
        AiDailyInsightScoring.Decision ordinaryWatch = new AiDailyInsightScoring.Decision(
                "WATCH", "WATCH", new BigDecimal("42"), "AI_DECISION_MISSING",
                BigDecimal.ZERO, 0, "LOW_SAMPLE");

        AiDailyInsightScoring.Decision result = AiDailyInsightV2ProjectorImpl.applyAvailabilityGate(
                sample, prediction, ordinaryWatch);

        assertThat(result.finalAction()).isEqualTo("UNAVAILABLE");
        assertThat(result.confidenceLevel()).isEqualTo("DATA_UNAVAILABLE");
    }

    @Test
    void projectsV2PredictionLabelsAndStructuredAiDecisionIntoDailyInsight() {
        AiSampleV2Mapper sampleMapper = mock(AiSampleV2Mapper.class);
        AiPredictionV2Mapper predictionMapper = mock(AiPredictionV2Mapper.class);
        AiLabelV2Mapper labelMapper = mock(AiLabelV2Mapper.class);
        AiFactorValueV2Mapper factorMapper = mock(AiFactorValueV2Mapper.class);
        AiFactorPerformanceV2Mapper performanceMapper = mock(AiFactorPerformanceV2Mapper.class);
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        AiAnalysisDecisionMapper decisionMapper = mock(AiAnalysisDecisionMapper.class);
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        LocalDateTime now = tradeDate.atTime(16, 10);

        AiSampleV2 sample = new AiSampleV2();
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

        AiPredictionV2 prediction = new AiPredictionV2();
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
        AiPredictionV2 challenger = new AiPredictionV2();
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

        List<AiLabelV2> labels = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            AiLabelV2 label = new AiLabelV2();
            label.stockCode = "600519";
            label.hitTarget = index < 8 ? 1 : 0;
            label.labelStatus = "VERIFIED";
            labels.add(label);
        }
        when(labelMapper.selectList(any())).thenReturn(labels);
        AiFactorValueV2 oldFactor = new AiFactorValueV2();
        oldFactor.sampleId = 21L;
        oldFactor.factorCode = "MOMENTUM_RETURN_5D";
        oldFactor.factorVersion = "1.0.0";
        oldFactor.evidence = "OLD_FACTOR_EVIDENCE";
        AiFactorValueV2 currentFactor = new AiFactorValueV2();
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

        AiDailyInsightV2Projector projector = new AiDailyInsightV2ProjectorImpl(
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
