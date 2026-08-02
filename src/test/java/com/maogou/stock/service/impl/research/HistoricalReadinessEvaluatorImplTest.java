package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiHistoricalReadinessFeatureMetric;
import com.maogou.stock.domain.entity.research.AiHistoricalReadinessSummary;
import com.maogou.stock.mapper.research.AiHistoricalReadinessMapper;
import com.maogou.stock.service.research.HistoricalReadinessEvaluator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalReadinessEvaluatorImplTest {

    private static final LocalDate START = LocalDate.of(2025, 11, 20);
    private static final LocalDate END = LocalDate.of(2026, 7, 31);
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 8, 1, 18, 0);

    @Test
    void calculatesReadyOnlyFromCurrentVersionFacts() {
        AiHistoricalReadinessMapper mapper = mock(AiHistoricalReadinessMapper.class);
        when(mapper.selectSummary(any(), any(), any(), any(), any(), any())).thenReturn(summary(90_100, 89_000));
        when(mapper.selectHorizonCounts(any(), any(), any(), any(), any(), any())).thenReturn(List.of(
                metric("1", 20_000), metric("2", 20_000), metric("3", 20_000), metric("5", 20_000)));
        when(mapper.selectRegimeDays(any(), any(), any(), any(), any())).thenReturn(List.of(
                metric("UP", 20), metric("DOWN", 20), metric("SIDEWAYS", 20)));
        when(mapper.selectClassDistribution(any(), any(), any(), any(), any(), any())).thenReturn(List.of(
                metric("UP", 30_000), metric("DOWN", 30_000), metric("SIDEWAYS", 30_000)));
        when(mapper.selectFeatureCoverage(any(), any(), any(), any(), any(), any())).thenReturn(List.of(
                feature("TREND", 100, 100)));
        when(mapper.countPointInTimeViolations(any(), any(), any(), any(), any())).thenReturn(0);
        when(mapper.countDuplicateLabels(any(), any(), any(), any(), any())).thenReturn(0);
        when(mapper.countMockSources(any(), any(), any(), any(), any())).thenReturn(0);
        when(mapper.countStaleSources(any(), any(), any(), any(), any())).thenReturn(0);
        when(mapper.countInferredFacts(any(), any(), any(), any(), any())).thenReturn(0);

        HistoricalReadinessEvaluator.Evaluation result = evaluator(mapper).evaluate(request());

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.maturityLevel()).isEqualTo("R1_HISTORICAL_FACTS_READY");
        assertThat(result.horizonCounts()).containsEntry(1, 20_000).containsEntry(5, 20_000);
        assertThat(result.blockingGaps()).isEmpty();
        assertThat(result.evidenceChecksum()).hasSize(64);
    }

    @Test
    void blocksMockAndPointInTimeRowsEvenWhenVolumeGatesPass() {
        AiHistoricalReadinessMapper mapper = mock(AiHistoricalReadinessMapper.class);
        when(mapper.selectSummary(any(), any(), any(), any(), any(), any())).thenReturn(summary(90_100, 89_000));
        when(mapper.selectHorizonCounts(any(), any(), any(), any(), any(), any())).thenReturn(List.of(
                metric("1", 20_000), metric("2", 20_000), metric("3", 20_000), metric("5", 20_000)));
        when(mapper.selectRegimeDays(any(), any(), any(), any(), any())).thenReturn(List.of(
                metric("UP", 20), metric("DOWN", 20), metric("SIDEWAYS", 20)));
        when(mapper.selectClassDistribution(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.selectFeatureCoverage(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.countPointInTimeViolations(any(), any(), any(), any(), any())).thenReturn(2);
        when(mapper.countDuplicateLabels(any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.countMockSources(any(), any(), any(), any(), any())).thenReturn(3);
        when(mapper.countStaleSources(any(), any(), any(), any(), any())).thenReturn(0);
        when(mapper.countInferredFacts(any(), any(), any(), any(), any())).thenReturn(0);

        HistoricalReadinessEvaluator.Evaluation result = evaluator(mapper).evaluate(request());

        assertThat(result.status()).isEqualTo("BLOCKED_BY_QUALITY");
        assertThat(result.blockingGaps()).contains(
                "POINT_IN_TIME_VIOLATIONS:2",
                "DUPLICATE_LABEL_BUSINESS_KEYS:1",
                "MOCK_SOURCE_ROWS:3");
    }

    private static HistoricalReadinessEvaluator evaluator(AiHistoricalReadinessMapper mapper) {
        return new HistoricalReadinessEvaluatorImpl(mapper, new ObjectMapper().findAndRegisterModules());
    }

    private static HistoricalReadinessEvaluator.Request request() {
        return new HistoricalReadinessEvaluator.Request(
                1L, "POINT_IN_TIME/1.1.0", "FACTOR/1.1.0", "LABEL/1.1.0",
                "CALENDAR/1.0.0", START, END, AS_OF);
    }

    private static AiHistoricalReadinessSummary summary(int eligible, int ready) {
        AiHistoricalReadinessSummary summary = new AiHistoricalReadinessSummary();
        summary.tradingDays = 120;
        summary.stockCount = 200;
        summary.tradabilityEligible = eligible;
        summary.tradabilityReady = ready;
        summary.universeEligible = eligible;
        summary.universeReady = eligible;
        summary.sectorEligible = eligible;
        summary.sectorReady = eligible;
        return summary;
    }

    private static AiHistoricalReadinessMapper.DimensionMetric metric(String key, int count) {
        AiHistoricalReadinessMapper.DimensionMetric metric = new AiHistoricalReadinessMapper.DimensionMetric();
        metric.dimensionKey = key;
        metric.metricCount = count;
        return metric;
    }

    private static AiHistoricalReadinessFeatureMetric feature(String code, int total, int ready) {
        AiHistoricalReadinessFeatureMetric metric = new AiHistoricalReadinessFeatureMetric();
        metric.factorCode = code;
        metric.totalCount = total;
        metric.readyCount = ready;
        metric.missingCount = total - ready;
        return metric;
    }
}
