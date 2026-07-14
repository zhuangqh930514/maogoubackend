package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.research.AiAnalysisReportPrediction;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiAnalysisResultPayload;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.research.AiAnalysisReportPredictionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAnalysisReportLineageTest {

    @Test
    void reanalysisCreatesNewImmutableVersionAndPreservesAllPredictionLinks() {
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        AiAnalysisReportPredictionMapper linkMapper = mock(AiAnalysisReportPredictionMapper.class);
        AiAnalysisReport previous = report(31L, 1, "原始提示词", "原始响应");
        when(reportMapper.lockUser(5L)).thenReturn(5L);
        when(reportMapper.selectLatestVersionForUpdate(5L, "600519", LocalDate.of(2026, 7, 14)))
                .thenReturn(previous);
        doAnswer(invocation -> {
            AiAnalysisReport value = invocation.getArgument(0);
            value.id = 32L;
            return 1;
        }).when(reportMapper).insert(any(AiAnalysisReport.class));

        AiAnalysisReport draft = report(null, null, "新提示词", "新响应");
        List<AiPrediction> predictions = List.of(
                prediction(101L, 1), prediction(102L, 2), prediction(103L, 3), prediction(105L, 5));

        AiAnalysisReportLineageWriter writer = new AiAnalysisReportLineageWriter(reportMapper, linkMapper);
        AiAnalysisReport persisted = writer.persistVersion(draft, predictions);

        assertThat(persisted.id).isEqualTo(32L);
        assertThat(persisted.reportVersion).isEqualTo(2);
        assertThat(persisted.supersedesReportId).isEqualTo(31L);
        assertThat(previous.rawPrompt).isEqualTo("原始提示词");
        assertThat(previous.rawResponse).isEqualTo("原始响应");
        assertThat(previous.reportVersion).isEqualTo(1);
        verify(reportMapper, never()).updateById(previous);

        ArgumentCaptor<AiAnalysisReportPrediction> links =
                ArgumentCaptor.forClass(AiAnalysisReportPrediction.class);
        verify(linkMapper, org.mockito.Mockito.times(4)).insert(links.capture());
        assertThat(links.getAllValues())
                .extracting(item -> item.predictionId)
                .containsExactly(101L, 102L, 103L, 105L);
        assertThat(links.getAllValues())
                .extracting(item -> item.purpose)
                .containsExactly("T1_SIGNAL", "T2_SIGNAL", "PRIMARY_RANKING", "RESEARCH_CONTEXT");
        assertThat(links.getAllValues())
                .extracting(item -> item.weight)
                .containsExactly(
                        new BigDecimal("0.200000"),
                        new BigDecimal("0.300000"),
                        new BigDecimal("0.500000"),
                        new BigDecimal("0.000000"));
    }

    @Test
    void missingCorePredictionForcesWatchAndT5NeverChangesThreeDayScore() {
        AiSample sample = new AiSample();
        sample.id = 11L;
        sample.dataQualityScore = new BigDecimal("95");
        sample.qualityStatus = "READY";
        sample.tradableStatus = "TRADABLE";

        AiAnalysisServiceImpl.FormalDecision incomplete = AiAnalysisServiceImpl.deriveFormalDecision(
                sample, Map.of(1, prediction(101L, 1)));
        assertThat(incomplete.finalAction()).isEqualTo("WATCH");
        assertThat(incomplete.systemScore()).isNull();
        assertThat(incomplete.unavailableReason()).isEqualTo("MISSING_T2_PREDICTION");

        Map<Integer, AiPrediction> complete = Map.of(
                1, prediction(101L, 1, "60", "40"),
                2, prediction(102L, 2, "70", "50"),
                3, prediction(103L, 3, "80", "55"),
                5, prediction(105L, 5, "0", "100"));
        AiAnalysisServiceImpl.FormalDecision decision =
                AiAnalysisServiceImpl.deriveFormalDecision(sample, complete);

        assertThat(decision.systemScore()).isEqualByComparingTo("73.0000");
        assertThat(decision.finalAction()).isEqualTo("BUY");
        assertThat(decision.riskScore()).isEqualByComparingTo("55");
    }

    @Test
    void llmPayloadCanExplainButCannotOverwriteFormalDecisionOrTradePlan() throws Exception {
        AiAnalysisReport report = report(null, null, "prompt", null);
        report.systemScore = new BigDecimal("73");
        report.finalAction = "BUY";
        report.riskScore = new BigDecimal("55");
        report.riskLevel = "MEDIUM";
        report.buySellPoints = "{\"source\":\"SYSTEM_CONDITIONAL_RULES\",\"action\":\"BUY\"}";
        AiAnalysisResultPayload payload = new AiAnalysisResultPayload(
                new AiAnalysisResultPayload.DecisionPayload(
                        "SELL", 0.99, "立即", "DOWN", "HIGH", "模型试图覆盖", List.of()),
                new AiAnalysisResultPayload.TechnicalAnalysisPayload(
                        "解释", null, null, null, null, "量能", null, null),
                new AiAnalysisResultPayload.RiskWarningPayload(
                        "风险解释", List.of(), List.of(), List.of(), "观察"),
                new AiAnalysisResultPayload.BuySellPointsPayload(
                        "SELL", List.of(), List.of(), "", "", "清仓"),
                new AiAnalysisResultPayload.PromptSummaryPayload("市场", "估值", "成长", "K线", "量能"),
                1);

        analysisService().applyPayload(report, payload);

        assertThat(report.systemScore).isEqualByComparingTo("73");
        assertThat(report.finalAction).isEqualTo("BUY");
        assertThat(report.riskScore).isEqualByComparingTo("55");
        assertThat(report.riskLevel).isEqualTo("MEDIUM");
        assertThat(report.buySellPoints).contains("SYSTEM_CONDITIONAL_RULES", "BUY").doesNotContain("SELL");
        assertThat(report.technicalAnalysis).contains("解释");
    }

    @Test
    void currentChampionNeverFallsBackToOlderCompletePredictionSet() {
        AiPrediction oldT1 = prediction(91L, 1);
        AiPrediction oldT2 = prediction(92L, 2);
        AiPrediction oldT3 = prediction(93L, 3);
        oldT1.strategyReleaseId = 20L;
        oldT2.strategyReleaseId = 20L;
        oldT3.strategyReleaseId = 20L;
        AiPrediction currentT1 = prediction(101L, 1);
        currentT1.strategyReleaseId = 21L;

        List<AiPrediction> selected = AiAnalysisServiceImpl.selectCurrentReleasePredictions(
                List.of(oldT1, oldT2, oldT3, currentT1), 21L);

        assertThat(selected).extracting(item -> item.id).containsExactly(101L);
    }

    private static AiAnalysisReport report(
            Long id,
            Integer version,
            String rawPrompt,
            String rawResponse
    ) {
        AiAnalysisReport report = new AiAnalysisReport();
        report.id = id;
        report.userId = 5L;
        report.sampleId = 11L;
        report.strategyReleaseId = 21L;
        report.stockCode = "600519";
        report.stockName = "贵州茅台";
        report.reportDate = LocalDate.of(2026, 7, 14);
        report.reportVersion = version;
        report.status = AnalysisStatus.SUCCESS;
        report.rawPrompt = rawPrompt;
        report.rawResponse = rawResponse;
        report.generatedAt = LocalDateTime.of(2026, 7, 14, 16, 30);
        report.createdAt = report.generatedAt;
        report.updatedAt = report.generatedAt;
        return report;
    }

    private static AiPrediction prediction(Long id, int horizon) {
        return prediction(id, horizon, "75", "45");
    }

    private static AiPrediction prediction(Long id, int horizon, String score, String risk) {
        AiPrediction prediction = new AiPrediction();
        prediction.id = id;
        prediction.sampleId = 11L;
        prediction.strategyReleaseId = 21L;
        prediction.horizonDays = horizon;
        prediction.score = new BigDecimal(score);
        prediction.riskScore = new BigDecimal(risk);
        prediction.calibratedConfidence = new BigDecimal("0.80");
        prediction.action = "BUY";
        prediction.targetDirection = "UP";
        return prediction;
    }

    private static AiAnalysisServiceImpl analysisService() {
        return new AiAnalysisServiceImpl(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, new ObjectMapper().findAndRegisterModules());
    }
}
