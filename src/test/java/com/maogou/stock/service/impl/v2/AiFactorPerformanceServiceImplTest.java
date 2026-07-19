package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDriftEvent;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.mapper.research.AiDriftEventMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.service.research.AiFactorPerformanceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiFactorPerformanceServiceImplTest {

    @Test
    void computesDailyRankIcAndWindowMetricsWithoutCrossingVersionHorizonOrRegime() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        List<AiFactorPerformanceService.Observation> observations = new ArrayList<>();
        observations.add(observation(101L, "600001", "2026-07-01", "-1.0", "-0.02", "-0.03"));
        observations.add(observation(102L, "600002", "2026-07-01", "-0.5", "-0.01", "-0.02"));
        observations.add(observation(103L, "600003", "2026-07-01", "1.0", "0.03", "-0.01"));
        observations.add(observation(104L, "600004", "2026-07-02", "-1.0", "-0.03", "-0.04"));
        observations.add(observation(105L, "600005", "2026-07-02", "-0.5", "-0.01", "-0.02"));
        observations.add(observation(106L, "600006", "2026-07-02", "1.0", "0.02", "-0.01"));

        AiFactorPerformanceService.EvaluationResult result = service.evaluateAndStore(batch(observations));

        assertThat(result.performances()).hasSize(1);
        AiFactorPerformance performance = result.performances().get(0);
        assertThat(performance.factorCode).isEqualTo("MOMENTUM_3D");
        assertThat(performance.factorVersion).isEqualTo("FACTOR_V2_1");
        assertThat(performance.horizonDays).isEqualTo(3);
        assertThat(performance.marketRegime).isEqualTo("BULL");
        assertThat(performance.windowStartDate).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(performance.windowEndDate).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(performance.sampleCount).isEqualTo(6);
        assertThat(performance.successCount).isEqualTo(6);
        assertThat(performance.rankIc).isEqualByComparingTo("1.000000");
        assertThat(performance.avgExcessReturn).isEqualByComparingTo("0.020000");
        assertThat(performance.avgAdverseReturn).isEqualByComparingTo("-0.021667");
        assertThat(performance.wilsonLowerBound).isGreaterThan(new BigDecimal("60"));
        assertThat(performance.confidenceLevel).isEqualTo("LOW_SAMPLE");
        assertThat(result.reweightEligibleFactorCodes()).isEmpty();
        assertThat(result.driftEvents()).isEmpty();
    }

    @Test
    void evaluatesInverseValuationFactorsWithTheSameOrientationUsedByPrediction() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        AiFactorPerformanceService.Observation lowPe = observation(
                111L, "600011", "2026-07-01", "-1.0", "0.02", "-0.01");
        AiFactorPerformanceService.Observation highPe = observation(
                112L, "600012", "2026-07-01", "1.0", "-0.02", "-0.03");
        lowPe.factor().factorCode = "FUNDAMENTAL_PE";
        lowPe.factor().factorGroup = "FUNDAMENTAL";
        lowPe.factor().rawValue = new BigDecimal("10");
        highPe.factor().factorCode = "FUNDAMENTAL_PE";
        highPe.factor().factorGroup = "FUNDAMENTAL";
        highPe.factor().rawValue = new BigDecimal("80");

        AiFactorPerformance performance = service.evaluateAndStore(
                batch(List.of(lowPe, highPe))).performances().get(0);

        assertThat(performance.successCount).isEqualTo(2);
        assertThat(performance.rankIc).isEqualByComparingTo("1.000000");
        assertThat(performance.avgExcessReturn).isEqualByComparingTo("0.020000");
    }

    @Test
    void emitsIdempotentCriticalEventsForPsiAndHitRateDriftAfterMinimumSampleSize() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        List<AiFactorPerformanceService.Observation> baseline = new ArrayList<>();
        List<AiFactorPerformanceService.Observation> current = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            BigDecimal baselineSignal = BigDecimal.valueOf(index - 10).divide(new BigDecimal("10"));
            if (baselineSignal.signum() == 0) {
                baselineSignal = new BigDecimal("0.05");
            }
            String alignedReturn = baselineSignal.signum() > 0 ? "0.02" : "-0.02";
            baseline.add(observation(
                    200L + index, String.format("60%04d", 100 + index),
                    index < 10 ? "2026-06-01" : "2026-06-02",
                    baselineSignal.toPlainString(), alignedReturn, "-0.02"));

            BigDecimal shiftedSignal = new BigDecimal("3.0").add(BigDecimal.valueOf(index, 2));
            current.add(observation(
                    300L + index, String.format("60%04d", 200 + index),
                    index < 10 ? "2026-07-01" : "2026-07-02",
                    shiftedSignal.toPlainString(), "-0.02", "-0.04"));
        }
        AiFactorPerformanceService.PerformanceBatch original = batch(current);
        AiFactorPerformanceService.PerformanceBatch withBaseline = new AiFactorPerformanceService.PerformanceBatch(
                original.factorVersion(), original.horizonDays(),
                original.marketRegime(), original.windowType(), original.windowStartDate(),
                original.windowEndDate(), original.observations(), baseline,
                original.detectorVersion(), original.thresholds(), original.evaluatedAt());

        AiFactorPerformanceService.EvaluationResult result = service.evaluateAndStore(withBaseline);

        AiFactorPerformance performance = result.performances().get(0);
        assertThat(performance.sampleCount).isEqualTo(20);
        assertThat(performance.confidenceLevel).isEqualTo("MEDIUM");
        assertThat(performance.psiScore).isGreaterThan(new BigDecimal("0.25"));
        assertThat(performance.driftStatus).isEqualTo("CRITICAL");
        assertThat(result.driftEvents()).extracting(event -> event.metricName)
                .containsExactlyInAnyOrder("PSI", "SUCCESS_RATE");
        assertThat(result.driftEvents()).allMatch(event -> "CRITICAL".equals(event.severity));
        assertThat(result.driftEvents()).allMatch(event -> event.eventFingerprint != null
                && event.evidenceJson != null && event.factorPerformanceId != null);
    }

    @Test
    void acceptsPersistedDriftEvidenceWithEquivalentJsonPropertyOrder() throws Exception {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        List<AiFactorPerformanceService.Observation> baseline = new ArrayList<>();
        List<AiFactorPerformanceService.Observation> current = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            baseline.add(observation(1000L + index, String.format("61%04d", index),
                    "2026-06-01", "1.0", "0.02", "-0.01"));
            current.add(observation(1100L + index, String.format("62%04d", index),
                    "2026-07-01", "3.0", "-0.02", "-0.04"));
        }
        AiFactorPerformanceService.PerformanceBatch original = batch(current);
        AiFactorPerformanceService.PerformanceBatch withBaseline = new AiFactorPerformanceService.PerformanceBatch(
                original.factorVersion(), original.horizonDays(), original.marketRegime(), original.windowType(),
                original.windowStartDate(), original.windowEndDate(), original.observations(), baseline,
                original.detectorVersion(), original.thresholds(), original.evaluatedAt());

        service.evaluateAndStore(withBaseline);
        ObjectMapper mapper = new ObjectMapper();
        for (AiDriftEvent event : fixture.driftDatabase) {
            var fields = new ArrayList<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>>();
            mapper.readTree(event.evidenceJson).fields().forEachRemaining(fields::add);
            java.util.Collections.reverse(fields);
            var reordered = mapper.createObjectNode();
            fields.forEach(field -> reordered.set(field.getKey(), field.getValue()));
            event.evidenceJson = mapper.writeValueAsString(reordered);
        }

        AiFactorPerformanceService.EvaluationResult repeated = service.evaluateAndStore(withBaseline);

        assertThat(repeated.driftEvents()).hasSameSizeAs(fixture.driftDatabase);
    }

    @Test
    void rejectsCrossVersionOrCrossHorizonObservationsInsteadOfSilentlyDroppingThem() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        AiFactorPerformanceService.Observation contaminated = observation(
                401L, "600401", "2026-07-01", "1.0", "0.02", "-0.01");
        contaminated.factor().factorVersion = "FACTOR_V1";
        contaminated.label().horizonTradingDays = 5;

        assertThatThrownBy(() -> service.evaluateAndStore(batch(List.of(contaminated))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本、周期或血缘");
    }

    @Test
    void includesFlatOutcomesAsMissesInsteadOfDroppingThemFromTheDenominator() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        AiFactorPerformanceService.Observation hit = observation(
                501L, "600501", "2026-07-01", "1.0", "0.02", "-0.01");
        AiFactorPerformanceService.Observation flat = observation(
                502L, "600502", "2026-07-01", "1.0", "0.00", "-0.01");

        AiFactorPerformance performance = service.evaluateAndStore(
                batch(List.of(hit, flat))).performances().get(0);

        assertThat(performance.sampleCount).isEqualTo(2);
        assertThat(performance.successCount).isEqualTo(1);
        assertThat(performance.successRate).isEqualByComparingTo("50.0000");
    }

    @Test
    void rejectsDuplicateSampleFactorLabelObservations() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        AiFactorPerformanceService.Observation duplicated = observation(
                601L, "600601", "2026-07-01", "1.0", "0.02", "-0.01");

        assertThatThrownBy(() -> service.evaluateAndStore(batch(List.of(duplicated, duplicated))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复观察");
    }

    @Test
    void repeatedEvaluationOfTheSameImmutableWindowReturnsTheOriginalRecord() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        AiFactorPerformanceService.PerformanceBatch batch = batch(List.of(
                observation(701L, "600701", "2026-07-01", "1.0", "0.02", "-0.01"),
                observation(702L, "600702", "2026-07-01", "-1.0", "-0.02", "-0.02")));

        AiFactorPerformance first = service.evaluateAndStore(batch).performances().get(0);
        AiFactorPerformance repeated = service.evaluateAndStore(batch).performances().get(0);

        assertThat(repeated.id).isEqualTo(first.id);
        assertThat(repeated.inputFingerprint).isEqualTo(first.inputFingerprint);
    }

    @Test
    void createsNextRevisionWhenLateEvidenceChangesTheSamePerformanceWindow() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        AiFactorPerformanceService.Observation observation = observation(
                801L, "600801", "2026-07-01", "1.0", "0.02", "-0.01");
        AiFactorPerformanceService.PerformanceBatch batch = batch(List.of(observation));
        AiFactorPerformance first = service.evaluateAndStore(batch).performances().get(0);
        observation.label().inputFingerprint = "tampered-label";

        AiFactorPerformance revised = service.evaluateAndStore(batch).performances().get(0);

        assertThat(revised.id).isNotEqualTo(first.id);
        assertThat(revised.revisionNo).isEqualTo(2);
        assertThat(revised.isCurrent).isEqualTo(1);
        assertThat(revised.supersedesPerformanceId).isEqualTo(first.id);
        assertThat(first.isCurrent).isZero();
        verify(fixture.performanceMapper).markSuperseded(List.of(first.id));
    }

    @Test
    void rejectsInvertedOrNegativeDriftThresholds() {
        Fixture fixture = fixture();
        AiFactorPerformanceService service = service(fixture);
        AiFactorPerformanceService.PerformanceBatch original = batch(List.of(
                observation(901L, "600901", "2026-07-01", "1.0", "0.02", "-0.01")));
        AiFactorPerformanceService.PerformanceBatch invalid = new AiFactorPerformanceService.PerformanceBatch(
                original.factorVersion(), original.horizonDays(),
                original.marketRegime(), original.windowType(), original.windowStartDate(),
                original.windowEndDate(), original.observations(), original.baselineObservations(),
                original.detectorVersion(), new AiFactorPerformanceService.DriftThresholds(
                new BigDecimal("0.25"), new BigDecimal("0.10"),
                new BigDecimal("-1"), new BigDecimal("25")), original.evaluatedAt());

        assertThatThrownBy(() -> service.evaluateAndStore(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("漂移阈值");
    }

    private static AiFactorPerformanceService service(Fixture fixture) {
        return new AiFactorPerformanceServiceImpl(
                fixture.performanceMapper, fixture.driftMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiFactorPerformanceService.PerformanceBatch batch(
            List<AiFactorPerformanceService.Observation> observations
    ) {
        return new AiFactorPerformanceService.PerformanceBatch(
                "FACTOR_V2_1", 3, "BULL", "ROLLING_20D",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2),
                observations, List.of(), "DRIFT_V2_1",
                new AiFactorPerformanceService.DriftThresholds(
                        new BigDecimal("0.10"), new BigDecimal("0.25"),
                        new BigDecimal("15"), new BigDecimal("25")),
                LocalDateTime.of(2026, 7, 20, 16, 0));
    }

    private static AiFactorPerformanceService.Observation observation(
            Long sampleId,
            String stockCode,
            String tradeDate,
            String factorValue,
            String excessReturn,
            String adverseReturn
    ) {
        AiSample sample = new AiSample();
        sample.id = sampleId;
        sample.userId = 5L;
        sample.stockCode = stockCode;
        sample.tradeDate = LocalDate.parse(tradeDate);
        sample.samplePhase = "AFTER_CLOSE";
        sample.marketRegime = "BULL";
        sample.sourceFingerprint = "sample-" + sampleId;

        AiFactorValue factor = new AiFactorValue();
        factor.id = sampleId + 1000;
        factor.factorDefinitionId = 51L;
        factor.userId = 5L;
        factor.sampleId = sampleId;
        factor.stockCode = stockCode;
        factor.factorCode = "MOMENTUM_3D";
        factor.factorVersion = "FACTOR_V2_1";
        factor.factorGroup = "MOMENTUM";
        factor.normalizedValue = new BigDecimal(factorValue);
        factor.rawValue = factor.normalizedValue;
        factor.missing = 0;
        factor.inputFingerprint = "factor-" + sampleId;

        AiSampleLabel label = new AiSampleLabel();
        label.id = sampleId + 2000;
        label.sampleId = sampleId;
        label.stockCode = stockCode;
        label.horizonTradingDays = 3;
        label.labelVersion = "LABEL/1.1.0";
        label.inputFingerprint = "label-" + sampleId;
        label.excessReturn = new BigDecimal(excessReturn);
        label.maxAdverseReturn = new BigDecimal(adverseReturn);
        label.executionStatus = "EXECUTED";
        label.labelStatus = "MATURED";
        label.verifiedAt = LocalDate.parse(tradeDate).plusDays(5).atTime(16, 0);
        return new AiFactorPerformanceService.Observation(sample, factor, label);
    }

    private static Fixture fixture() {
        AiFactorPerformanceMapper performanceMapper = mock(AiFactorPerformanceMapper.class);
        AiDriftEventMapper driftMapper = mock(AiDriftEventMapper.class);
        List<AiFactorPerformance> database = new ArrayList<>();
        List<AiDriftEvent> driftDatabase = new ArrayList<>();
        AtomicLong ids = new AtomicLong(700);
        when(performanceMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiFactorPerformance> items = invocation.getArgument(0);
            for (AiFactorPerformance item : items) {
                boolean exists = database.stream().anyMatch(existing ->
                        existing.factorDefinitionId.equals(item.factorDefinitionId)
                                && existing.horizonDays.equals(item.horizonDays)
                                && existing.marketRegime.equals(item.marketRegime)
                                && existing.windowType.equals(item.windowType)
                                && existing.windowStartDate.equals(item.windowStartDate)
                                && existing.windowEndDate.equals(item.windowEndDate)
                                && existing.revisionNo.equals(item.revisionNo));
                if (!exists) {
                    item.id = ids.incrementAndGet();
                    database.add(item);
                }
            }
            return items.size();
        });
        when(performanceMapper.selectCurrentWindowForUpdate(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> database.stream()
                .filter(item -> item.isCurrent == 1).toList());
        when(performanceMapper.markSuperseded(anyList())).thenAnswer(invocation -> {
            List<Long> idsToSupersede = invocation.getArgument(0);
            database.stream().filter(item -> idsToSupersede.contains(item.id))
                    .forEach(item -> item.isCurrent = 0);
            return idsToSupersede.size();
        });
        when(performanceMapper.selectWindowForShare(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> database.stream()
                .filter(item -> item.isCurrent == 1).toList());
        when(driftMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiDriftEvent> items = invocation.getArgument(0);
            for (AiDriftEvent item : items) {
                boolean exists = driftDatabase.stream().anyMatch(existing ->
                        existing.eventFingerprint.equals(item.eventFingerprint));
                if (!exists) {
                    item.id = ids.incrementAndGet();
                    driftDatabase.add(item);
                }
            }
            return items.size();
        });
        when(driftMapper.selectByFingerprintsForShare(anyList())).thenAnswer(invocation -> {
                    List<String> fingerprints = invocation.getArgument(0);
                    return driftDatabase.stream()
                            .filter(item -> fingerprints.contains(item.eventFingerprint)).toList();
                });
        return new Fixture(performanceMapper, driftMapper, driftDatabase);
    }

    private record Fixture(
            AiFactorPerformanceMapper performanceMapper,
            AiDriftEventMapper driftMapper,
            List<AiDriftEvent> driftDatabase
    ) {
    }
}
