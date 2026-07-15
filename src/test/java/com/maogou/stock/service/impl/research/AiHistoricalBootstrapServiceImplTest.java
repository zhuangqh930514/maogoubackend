package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiHistoricalBootstrapService;
import com.maogou.stock.service.research.HistoricalUniverseSourceService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiHistoricalBootstrapServiceImplTest {

    private static final List<String> REPLAY_STEPS = List.of(
            "BUILD_SAMPLES", "MATURE_SAMPLE_LABELS", "COMPUTE_FACTORS",
            "GENERATE_PREDICTIONS", "EVALUATE_PREDICTIONS");

    @Test
    void rejectsMissingHistoricalUniverseWithoutUsingCurrentUniverseOrFetchingData() {
        Fixture fixture = fixture();
        LocalDate missingDate = LocalDate.of(2025, 1, 6);
        when(fixture.sourceService.load(any(), any())).thenReturn(
                new HistoricalUniverseSourceService.HistoricalDayEvidence(
                        "MISSING_HISTORICAL_UNIVERSE", missingDate, missingDate.atTime(16, 0),
                        null, null, 0, null, List.of("缺少当日有效指数成分")));

        AiHistoricalBootstrapService.BootstrapResult result = service(fixture).run(request(
                missingDate, missingDate.plusDays(3)));

        assertThat(result.status()).isEqualTo("MISSING_HISTORICAL_UNIVERSE");
        assertThat(result.errors()).singleElement().asString().contains("缺少当日有效指数成分");
        assertThat(result.run().pipelineType).isEqualTo("GLOBAL_HISTORICAL_BOOTSTRAP");
        verify(fixture.executor, never()).execute(anyString(), any());
    }

    @Test
    void resumesTheSecondTwentyDayCheckpointWithoutRepeatingCompletedDays() {
        Fixture fixture = fixture();
        List<LocalDate> dates = tradingDates(45);
        when(fixture.sourceService.load(any(), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            if (!dates.contains(date)) {
                return new HistoricalUniverseSourceService.HistoricalDayEvidence(
                        "NOT_TRADING_DAY", date, date.atTime(16, 0),
                        null, null, 0, "calendar-" + date, List.of());
            }
            return ready(date, dates.indexOf(date) + 1L);
        });
        Map<LocalDate, Integer> executions = new LinkedHashMap<>();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        LocalDate failedDate = dates.get(24);
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            String step = invocation.getArgument(0);
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            executions.merge(context.tradeDate(), 1, Integer::sum);
            if (failedDate.equals(context.tradeDate()) && "COMPUTE_FACTORS".equals(step)
                    && failOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated checkpoint failure");
            }
            return outcome(step, context.tradeDate());
        });

        AiHistoricalBootstrapService service = service(fixture);
        AiHistoricalBootstrapService.BootstrapResult first = service.run(request(
                dates.get(0), dates.get(dates.size() - 1)));
        AiHistoricalBootstrapService.BootstrapResult recovered = service(fixture).run(request(
                dates.get(0), dates.get(dates.size() - 1)));

        assertThat(first.status()).isEqualTo("FAILED");
        assertThat(recovered.status()).isEqualTo("SUCCESS");
        assertThat(recovered.run().id).isEqualTo(first.run().id);
        assertThat(recovered.checkpoints()).hasSize(3);
        assertThat(recovered.checkpoints()).allMatch(step -> "SUCCESS".equals(step.status));
        assertThat(recovered.processedTradingDays()).isEqualTo(45);
        for (int index = 0; index < 24; index++) {
            assertThat(executions.get(dates.get(index))).isEqualTo(REPLAY_STEPS.size());
        }
        assertThat(executions.get(failedDate)).isEqualTo(REPLAY_STEPS.size() + 3);
        for (int index = 25; index < dates.size(); index++) {
            assertThat(executions.get(dates.get(index))).isEqualTo(REPLAY_STEPS.size());
        }
        assertThat(fixture.runStore).hasSize(1);
        assertThat(fixture.stepStore).hasSize(3);
    }

    private static AiHistoricalBootstrapService service(Fixture fixture) {
        return new AiHistoricalBootstrapServiceImpl(
                fixture.runMapper, fixture.stepMapper, fixture.sourceService,
                fixture.executor, new ObjectMapper().findAndRegisterModules());
    }

    private static AiHistoricalBootstrapService.BootstrapRequest request(
            LocalDate startDate,
            LocalDate endDate
    ) {
        return new AiHistoricalBootstrapService.BootstrapRequest(
                startDate, endDate, 11L, null,
                "HISTORICAL:" + startDate + ":" + endDate,
                LocalDateTime.of(2026, 7, 14, 10, 0));
    }

    private static HistoricalUniverseSourceService.HistoricalDayEvidence ready(
            LocalDate date,
            long sequence
    ) {
        return new HistoricalUniverseSourceService.HistoricalDayEvidence(
                "READY", date, date.atTime(16, 0), 100L + sequence,
                200L + sequence, 200, "historical-" + date, List.of());
    }

    private static AiGlobalDailyResearchExecutor.StepOutcome outcome(String step, LocalDate date) {
        return new AiGlobalDailyResearchExecutor.StepOutcome(
                "SUCCESS", 1, 1, 0, "{\"tradeDate\":\"" + date + "\"}",
                step + "-" + date, List.of(), null, null);
    }

    private static List<LocalDate> tradingDates(int count) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = LocalDate.of(2025, 1, 2);
        while (dates.size() < count) {
            if (cursor.getDayOfWeek().getValue() <= 5) {
                dates.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }
        return dates;
    }

    private static Fixture fixture() {
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiPipelineStepMapper stepMapper = mock(AiPipelineStepMapper.class);
        HistoricalUniverseSourceService sourceService = mock(HistoricalUniverseSourceService.class);
        AiGlobalDailyResearchExecutor executor = mock(AiGlobalDailyResearchExecutor.class);
        List<AiPipelineRun> runs = new ArrayList<>();
        List<AiPipelineStep> steps = new ArrayList<>();
        AtomicLong ids = new AtomicLong(1000);
        when(runMapper.insertIgnore(any(AiPipelineRun.class))).thenAnswer(invocation -> {
            AiPipelineRun candidate = invocation.getArgument(0);
            if (runs.stream().noneMatch(run -> run.idempotencyKey.equals(candidate.idempotencyKey))) {
                candidate.id = ids.incrementAndGet();
                runs.add(candidate);
            }
            return 1;
        });
        when(runMapper.selectByIdempotencyForUpdate(anyString())).thenAnswer(invocation -> runs.stream()
                .filter(run -> run.idempotencyKey.equals(invocation.getArgument(0)))
                .findFirst().orElse(null));
        when(runMapper.claimExecution(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(runMapper.renewExecution(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(runMapper.updateStateFenced(any(AiPipelineRun.class), anyString(), any())).thenReturn(1);
        when(runMapper.releaseExecution(anyLong(), anyString(), any())).thenReturn(1);
        when(stepMapper.insertIgnore(any(AiPipelineStep.class))).thenAnswer(invocation -> {
            AiPipelineStep candidate = invocation.getArgument(0);
            if (steps.stream().noneMatch(step -> step.pipelineRunId.equals(candidate.pipelineRunId)
                    && step.stepKey.equals(candidate.stepKey))) {
                candidate.id = ids.incrementAndGet();
                steps.add(candidate);
            }
            return 1;
        });
        when(stepMapper.selectByRunIdForUpdate(anyLong())).thenAnswer(invocation -> steps.stream()
                .filter(step -> step.pipelineRunId.equals(invocation.getArgument(0)))
                .sorted(java.util.Comparator.comparing(step -> step.stepOrder))
                .toList());
        when(stepMapper.updateStateFenced(any(AiPipelineStep.class), anyString(), any())).thenReturn(1);
        return new Fixture(runMapper, stepMapper, sourceService, executor, runs, steps);
    }

    private record Fixture(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            HistoricalUniverseSourceService sourceService,
            AiGlobalDailyResearchExecutor executor,
            List<AiPipelineRun> runStore,
            List<AiPipelineStep> stepStore
    ) {
    }
}
