package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiWalkForwardBaseline;
import com.maogou.stock.domain.entity.research.AiWalkForwardFold;
import com.maogou.stock.domain.entity.research.AiWalkForwardRun;
import com.maogou.stock.mapper.research.AiWalkForwardBaselineMapper;
import com.maogou.stock.mapper.research.AiWalkForwardFoldMapper;
import com.maogou.stock.mapper.research.AiWalkForwardRunMapper;
import com.maogou.stock.service.research.AiWalkForwardService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiWalkForwardServiceImplTest {

    @Test
    void createsChronologicalFoldsAndPurgesLabelsThatCrossBoundaries() {
        Fixture fixture = fixture();
        AiWalkForwardService service = service(fixture);
        List<LocalDate> dates = dates(30);

        AiWalkForwardService.WalkForwardResult result = service.runAndStore(request(dates));

        assertThat(result.run().status).isEqualTo("COMPLETED");
        assertThat(result.folds()).hasSize(2);
        AiWalkForwardFold first = result.folds().get(0);
        assertThat(first.trainStartDate).isEqualTo(dates.get(0));
        assertThat(first.trainEndDate).isEqualTo(dates.get(7));
        assertThat(first.validationStartDate).isEqualTo(dates.get(10));
        assertThat(first.validationEndDate).isEqualTo(dates.get(13));
        assertThat(first.testStartDate).isEqualTo(dates.get(15));
        assertThat(first.testEndDate).isEqualTo(dates.get(18));
        assertThat(first.trainEndDate).isBefore(first.validationStartDate);
        assertThat(first.validationEndDate).isBefore(first.testStartDate);

        AiWalkForwardService.FoldExecution evidence = result.executions().get(0);
        assertThat(evidence.trainSampleIds()).doesNotContain(8L, 9L, 10L);
        assertThat(evidence.validationSampleIds()).doesNotContain(14L);
        assertThat(evidence.trainSampleIds()).allMatch(id ->
                request(dates).observations().get(id.intValue() - 1).labelAvailableDate()
                        .isBefore(first.validationStartDate));
        assertThat(evidence.validationSampleIds()).allMatch(id ->
                request(dates).observations().get(id.intValue() - 1).labelAvailableDate()
                        .isBefore(first.testStartDate));
        assertThat(evidence.trainSampleIds()).doesNotContainAnyElementsOf(evidence.validationSampleIds());
        assertThat(evidence.validationSampleIds()).doesNotContainAnyElementsOf(evidence.testSampleIds());
    }

    @Test
    void persistsCandidateMetricsConfidenceIntervalsAndFourRealBaselines() throws Exception {
        Fixture fixture = fixture();
        AiWalkForwardService service = service(fixture);

        AiWalkForwardService.WalkForwardResult result = service.runAndStore(request(dates(30)));

        assertThat(result.baselines()).hasSize(8);
        assertThat(result.baselines()).extracting(item -> item.baselineType).containsOnly(
                "INDEX", "EQUAL_WEIGHT_UNIVERSE", "MOMENTUM", "CHAMPION");
        assertThat(result.baselines().stream()
                .filter(item -> "INDEX".equals(item.baselineType)))
                .allMatch(item -> "000300".equals(item.benchmarkCode));
        assertThat(result.baselines()).allMatch(item -> item.metricsJson.contains("totalReturn")
                && item.navJson.contains("nav"));
        assertThat(result.folds()).allMatch(item -> item.metricsJson.contains("candidateTotalReturn"));
        assertThat(result.folds()).allMatch(item -> item.confidenceIntervalJson.contains("lower95")
                && item.confidenceIntervalJson.contains("upper95"));
        var aggregate = new ObjectMapper().readTree(result.run().aggregateMetricsJson);
        assertThat(aggregate.has("meanTestReturn")).isTrue();
        assertThat(aggregate.path("confidenceInterval").has("lower95")).isTrue();
        assertThat(aggregate.path("confidenceInterval").has("upper95")).isTrue();
    }

    @Test
    void rerunningTheSameVersionedExperimentReturnsTheOriginalImmutableArtifacts() {
        Fixture fixture = fixture();
        AiWalkForwardService service = service(fixture);
        AiWalkForwardService.WalkForwardRequest request = request(dates(30));

        AiWalkForwardService.WalkForwardResult first = service.runAndStore(request);
        AiWalkForwardService.WalkForwardResult repeated = service.runAndStore(request);

        assertThat(repeated.run().id).isEqualTo(first.run().id);
        assertThat(repeated.folds()).extracting(item -> item.id)
                .containsExactlyElementsOf(first.folds().stream().map(item -> item.id).toList());
        assertThat(repeated.baselines()).extracting(item -> item.id)
                .containsExactlyElementsOf(first.baselines().stream().map(item -> item.id).toList());
    }

    @Test
    void rejectsDifferentDatasetLineageReusingTheSameRunKey() {
        Fixture fixture = fixture();
        AiWalkForwardService service = service(fixture);
        AiWalkForwardService.WalkForwardRequest original = request(dates(30));
        service.runAndStore(original);
        List<AiWalkForwardService.Observation> changed = new ArrayList<>(original.observations());
        AiWalkForwardService.Observation first = changed.get(0);
        changed.set(0, new AiWalkForwardService.Observation(
                first.sampleId(), first.tradeDate(), first.labelAvailableDate(), first.stockCode(),
                first.realizedNetReturn(), first.strategyScore(), first.momentumScore(),
                first.championScore(), "changed-lineage"));
        AiWalkForwardService.WalkForwardRequest conflicting = new AiWalkForwardService.WalkForwardRequest(
                original.userId(), original.trainingDatasetId(), original.strategyReleaseId(),
                original.modelVersionId(), original.runKey(), original.runVersion(), original.objective(),
                original.horizonDays(), original.purgeDays(), original.embargoDays(),
                original.foldCount(), original.randomSeed(), original.config(), changed,
                original.benchmarkCode(), original.benchmark(), original.evaluatedAt());

        assertThatThrownBy(() -> service.runAndStore(conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变 Walk-forward 运行冲突");
    }

    private static AiWalkForwardService service(Fixture fixture) {
        return new AiWalkForwardServiceImpl(
                fixture.runMapper, fixture.foldMapper, fixture.baselineMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiWalkForwardService.WalkForwardRequest request(List<LocalDate> dates) {
        List<AiWalkForwardService.Observation> observations = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            LocalDate labelAvailableDate = index + 3 < dates.size()
                    ? dates.get(index + 3) : dates.get(index).plusDays(10);
            observations.add(new AiWalkForwardService.Observation(
                    (long) index + 1, dates.get(index), labelAvailableDate,
                    String.format("60%04d", index),
                    BigDecimal.valueOf(index % 5 - 2, 2),
                    BigDecimal.valueOf(index % 7),
                    BigDecimal.valueOf(index % 6),
                    BigDecimal.valueOf(index % 4),
                    "observation-" + index));
        }
        List<AiWalkForwardService.BenchmarkPoint> benchmark = dates.stream()
                .map(date -> new AiWalkForwardService.BenchmarkPoint(
                        date, new BigDecimal("0.001"), "benchmark-" + date))
                .toList();
        return new AiWalkForwardService.WalkForwardRequest(
                5L, 11L, 21L, null, "WF-202607", "WF_V2_1", "EXCESS_RETURN",
                3, 2, 1, 2, 930514L,
                new AiWalkForwardService.WalkForwardConfig(8, 4, 4, 4, 2, 200),
                observations, "000300", benchmark, LocalDateTime.of(2026, 7, 31, 16, 0));
    }

    private static List<LocalDate> dates(int count) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = LocalDate.of(2026, 6, 1);
        while (dates.size() < count) {
            if (cursor.getDayOfWeek().getValue() <= 5) {
                dates.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }
        return dates;
    }

    private static Fixture fixture() {
        AiWalkForwardRunMapper runMapper = mock(AiWalkForwardRunMapper.class);
        AiWalkForwardFoldMapper foldMapper = mock(AiWalkForwardFoldMapper.class);
        AiWalkForwardBaselineMapper baselineMapper = mock(AiWalkForwardBaselineMapper.class);
        AtomicLong ids = new AtomicLong(1000);
        List<AiWalkForwardRun> runs = new ArrayList<>();
        List<AiWalkForwardFold> folds = new ArrayList<>();
        List<AiWalkForwardBaseline> baselines = new ArrayList<>();
        when(runMapper.insertImmutable(org.mockito.ArgumentMatchers.any(AiWalkForwardRun.class)))
                .thenAnswer(invocation -> {
                    AiWalkForwardRun run = invocation.getArgument(0);
                    boolean exists = runs.stream().anyMatch(item ->
                            item.userId.equals(run.userId) && item.runKey.equals(run.runKey));
                    if (!exists) {
                        run.id = ids.incrementAndGet();
                        runs.add(run);
                    }
                    return 1;
                });
        when(runMapper.selectByRunKeyForShare(anyLong(), anyString()))
                .thenAnswer(invocation -> runs.isEmpty() ? null : runs.get(0));
        when(foldMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiWalkForwardFold> items = invocation.getArgument(0);
            items.forEach(item -> {
                boolean exists = folds.stream().anyMatch(existing ->
                        existing.walkForwardRunId.equals(item.walkForwardRunId)
                                && existing.foldNo.equals(item.foldNo));
                if (!exists) {
                    item.id = ids.incrementAndGet();
                    folds.add(item);
                }
            });
            return items.size();
        });
        when(foldMapper.selectByRunIdForShare(anyLong())).thenAnswer(invocation -> List.copyOf(folds));
        when(baselineMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiWalkForwardBaseline> items = invocation.getArgument(0);
            items.forEach(item -> {
                boolean exists = baselines.stream().anyMatch(existing ->
                        existing.walkForwardFoldId.equals(item.walkForwardFoldId)
                                && existing.baselineKey.equals(item.baselineKey));
                if (!exists) {
                    item.id = ids.incrementAndGet();
                    baselines.add(item);
                }
            });
            return items.size();
        });
        when(baselineMapper.selectByRunIdForShare(anyLong()))
                .thenAnswer(invocation -> List.copyOf(baselines));
        return new Fixture(runMapper, foldMapper, baselineMapper);
    }

    private record Fixture(
            AiWalkForwardRunMapper runMapper,
            AiWalkForwardFoldMapper foldMapper,
            AiWalkForwardBaselineMapper baselineMapper
    ) {
    }
}
