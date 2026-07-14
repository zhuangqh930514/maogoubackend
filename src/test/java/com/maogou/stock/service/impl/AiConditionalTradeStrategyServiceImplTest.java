package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiTradePlanReview;
import com.maogou.stock.domain.entity.research.AiAnalysisReportPrediction;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.AiTradePlanReviewMapper;
import com.maogou.stock.mapper.research.AiAnalysisReportPredictionMapper;
import com.maogou.stock.mapper.research.AiPredictionEvaluationMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.service.TradingCalendarService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiConditionalTradeStrategyServiceImplTest {

    @Test
    void failedReportDoesNotCreateReviewsThatCanNeverMature() {
        AiTradePlanReviewMapper reviewMapper = mock(AiTradePlanReviewMapper.class);
        AiAnalysisReport report = new AiAnalysisReport();
        report.id = 9L;
        report.status = AnalysisStatus.FAILED;
        AiConditionalStrategyPayload payload = new AiConditionalStrategyPayload(
                "TEST", LocalDate.of(2026, 7, 14), LocalDateTime.of(2026, 7, 14, 16, 0),
                null, null, null, List.of(), List.of(), List.of(), null, null, List.of());

        service(reviewMapper, mock(AiAnalysisReportMapper.class)).initializeReviews(report, payload);

        verifyNoInteractions(reviewMapper);
    }

    @Test
    void verificationReadsOnlyMaturePendingReviewQueueBeforeLoadingReports() {
        AiTradePlanReviewMapper reviewMapper = mock(AiTradePlanReviewMapper.class);
        AiAnalysisReportMapper reportMapper = mock(AiAnalysisReportMapper.class);
        when(reviewMapper.selectList(any())).thenReturn(List.of());

        var result = service(reviewMapper, reportMapper).verifyMatured(5L, LocalDate.of(2026, 7, 14));

        assertThat(result.processedCount()).isZero();
        verifyNoInteractions(reportMapper);
        ArgumentCaptor<Wrapper<AiTradePlanReview>> captor = wrapperCaptor();
        verify(reviewMapper).selectList(captor.capture());
        QueryWrapper<AiTradePlanReview> query = (QueryWrapper<AiTradePlanReview>) captor.getValue();
        assertThat(query.getSqlSegment()).contains("status", "outcome_trade_date");
        assertThat(query.getParamNameValuePairs().values())
                .contains("PENDING", LocalDate.of(2026, 7, 14));
    }

    @Test
    void reviewsBindMatchingHorizonPredictionLabelEvaluationAndRuleConfig() {
        AiTradePlanReviewMapper reviewMapper = mock(AiTradePlanReviewMapper.class);
        AiAnalysisReportPredictionMapper linkMapper = mock(AiAnalysisReportPredictionMapper.class);
        AiPredictionMapper predictionMapper = mock(AiPredictionMapper.class);
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AiPredictionEvaluationMapper evaluationMapper = mock(AiPredictionEvaluationMapper.class);
        when(reviewMapper.selectOne(any())).thenReturn(null);
        when(linkMapper.selectByReport(5L, 9L)).thenReturn(List.of(
                link(101L), link(102L), link(103L)));
        when(predictionMapper.selectBatchIds(List.of(101L, 102L, 103L))).thenReturn(List.of(
                prediction(101L, 1), prediction(102L, 2), prediction(103L, 3)));
        when(labelMapper.selectForReview(11L, 1)).thenReturn(label(201L, 1));
        when(labelMapper.selectForReview(11L, 2)).thenReturn(label(202L, 2));
        when(labelMapper.selectForReview(11L, 3)).thenReturn(label(203L, 3));
        when(evaluationMapper.selectForReview(101L, 201L)).thenReturn(evaluation(301L));
        when(evaluationMapper.selectForReview(102L, 202L)).thenReturn(evaluation(302L));
        when(evaluationMapper.selectForReview(103L, 203L)).thenReturn(evaluation(303L));

        AiAnalysisReport report = new AiAnalysisReport();
        report.id = 9L;
        report.userId = 5L;
        report.sampleId = 11L;
        report.stockCode = "600519";
        report.reportDate = LocalDate.of(2026, 7, 14);
        report.status = AnalysisStatus.SUCCESS;
        AiConditionalStrategyPayload payload = payloadWithRuleConfig(88L);

        service(reviewMapper, mock(AiAnalysisReportMapper.class), linkMapper, predictionMapper,
                labelMapper, evaluationMapper).initializeReviews(report, payload);

        ArgumentCaptor<AiTradePlanReview> captor = ArgumentCaptor.forClass(AiTradePlanReview.class);
        verify(reviewMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(item -> item.horizonDays)
                .containsExactly(1, 2, 3);
        assertThat(captor.getAllValues()).extracting(item -> item.predictionId)
                .containsExactly(101L, 102L, 103L);
        assertThat(captor.getAllValues()).extracting(item -> item.sampleLabelId)
                .containsExactly(201L, 202L, 203L);
        assertThat(captor.getAllValues()).extracting(item -> item.predictionEvaluationId)
                .containsExactly(301L, 302L, 303L);
        assertThat(captor.getAllValues()).allMatch(item -> item.tradeRuleConfigId.equals(88L));
    }

    @Test
    void noTriggerNeverEntersActionWinRate() {
        AiTradePlanReview noTrigger = new AiTradePlanReview();
        noTrigger.status = "NO_TRIGGER";
        noTrigger.actionEffective = null;
        noTrigger.triggeredRuleCode = null;
        noTrigger.tradeRuleConfigId = 88L;

        assertThat(AiConditionalTradeStrategyServiceImpl.entersActionPerformance(noTrigger)).isFalse();

        noTrigger.status = "VERIFIED";
        noTrigger.actionEffective = 1;
        noTrigger.triggeredRuleCode = "BREAKOUT";
        assertThat(AiConditionalTradeStrategyServiceImpl.entersActionPerformance(noTrigger)).isTrue();
    }

    @Test
    void actionOutcomeRejectsSameDayLookAheadAndUsesFollowingTradingDay() {
        KlinePointResponse trigger = kline(LocalDate.of(2026, 7, 14), "10", "10.5", "9.8");
        KlinePointResponse sameDay = kline(LocalDate.of(2026, 7, 14), "10.2", "10.8", "9.9");
        assertThatThrownBy(() -> AiConditionalTradeStrategyServiceImpl.outcomeMetrics(trigger, sameDay))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("触发日后");

        KlinePointResponse nextDay = kline(LocalDate.of(2026, 7, 15), "10.5", "11", "9.5");
        var metrics = AiConditionalTradeStrategyServiceImpl.outcomeMetrics(trigger, nextDay);
        assertThat(metrics.postTriggerReturn()).isEqualByComparingTo("5.0000");
        assertThat(metrics.maxFavorableReturn()).isEqualByComparingTo("10.0000");
        assertThat(metrics.maxAdverseReturn()).isEqualByComparingTo("-5.0000");
    }

    @Test
    void unifiedSeedCapitalRiskAliasMapsToEngineFundComponent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var normalized = service(
                mock(AiTradePlanReviewMapper.class), mock(AiAnalysisReportMapper.class))
                .normalizedRuleOverrides(mapper.readTree("""
                        {"mode":"IF_THEN_ONLY","riskWeights":{"market":0.30,"sector":0.20,
                        "technical":0.30,"capital":0.20}}
                        """));

        assertThat(normalized.has("mode")).isFalse();
        assertThat(normalized.path("riskWeights").has("capital")).isFalse();
        assertThat(normalized.path("riskWeights").path("fund").decimalValue())
                .isEqualByComparingTo("0.20");
    }

    private static AiConditionalTradeStrategyServiceImpl service(
            AiTradePlanReviewMapper reviewMapper,
            AiAnalysisReportMapper reportMapper
    ) {
        return service(reviewMapper, reportMapper, null, null, null, null);
    }

    private static AiConditionalTradeStrategyServiceImpl service(
            AiTradePlanReviewMapper reviewMapper,
            AiAnalysisReportMapper reportMapper,
            AiAnalysisReportPredictionMapper linkMapper,
            AiPredictionMapper predictionMapper,
            AiSampleLabelMapper labelMapper,
            AiPredictionEvaluationMapper evaluationMapper
    ) {
        TradingCalendarService calendarService = mock(TradingCalendarService.class);
        when(calendarService.isTradingDay(any())).thenReturn(true);
        return new AiConditionalTradeStrategyServiceImpl(
                null,
                reviewMapper,
                null,
                reportMapper,
                null,
                null,
                null,
                null,
                null,
                linkMapper,
                labelMapper,
                evaluationMapper,
                predictionMapper,
                null,
                calendarService,
                null,
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiConditionalStrategyPayload payloadWithRuleConfig(Long configId) {
        List<AiConditionalStrategyPayload.HorizonPlan> plans = List.of(
                plan(1), plan(2), plan(3));
        return new AiConditionalStrategyPayload(
                "1.0", LocalDate.of(2026, 7, 14), LocalDateTime.of(2026, 7, 14, 16, 0),
                new AiConditionalStrategyPayload.ResearchLineage(
                        11L, 103L, 21L, "RULE_BASELINE/1.0.0", "1.0.0",
                        configId, "CONDITIONAL_RULE/1.0.0", "fingerprint", null, null),
                null, null, plans, List.of(), List.of(), null, null, List.of());
    }

    private static AiConditionalStrategyPayload.HorizonPlan plan(int horizon) {
        return new AiConditionalStrategyPayload.HorizonPlan(
                horizon, "T+" + horizon, "条件验证", "WAIT", "WATCH", List.of());
    }

    private static AiAnalysisReportPrediction link(Long predictionId) {
        AiAnalysisReportPrediction link = new AiAnalysisReportPrediction();
        link.userId = 5L;
        link.reportId = 9L;
        link.predictionId = predictionId;
        return link;
    }

    private static AiPrediction prediction(Long id, int horizon) {
        AiPrediction prediction = new AiPrediction();
        prediction.id = id;
        prediction.sampleId = 11L;
        prediction.horizonDays = horizon;
        return prediction;
    }

    private static AiSampleLabel label(Long id, int horizon) {
        AiSampleLabel label = new AiSampleLabel();
        label.id = id;
        label.sampleId = 11L;
        label.horizonTradingDays = horizon;
        return label;
    }

    private static AiPredictionEvaluation evaluation(Long id) {
        AiPredictionEvaluation evaluation = new AiPredictionEvaluation();
        evaluation.id = id;
        return evaluation;
    }

    private static KlinePointResponse kline(
            LocalDate date,
            String close,
            String high,
            String low
    ) {
        return new KlinePointResponse(
                date,
                new java.math.BigDecimal(close),
                new java.math.BigDecimal(close),
                new java.math.BigDecimal(low),
                new java.math.BigDecimal(high),
                0L,
                java.math.BigDecimal.ZERO);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<AiTradePlanReview>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }
}
