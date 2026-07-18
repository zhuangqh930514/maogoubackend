package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiHistoricalBootstrapService;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import com.maogou.stock.service.research.AiMonthlyTrainingRunner;
import com.maogou.stock.service.research.AiResearchCycleResult;
import com.maogou.stock.service.research.AiWeeklyEvolutionRunner;
import com.maogou.stock.service.research.HistoricalUniverseSourceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiHistoricalBootstrapServiceImplTest {

    private static final List<String> REPLAY_STEPS = List.of(
            "BUILD_SAMPLES", "COMPUTE_FACTORS", "GENERATE_PREDICTIONS");

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
        assertThat(executions.get(failedDate)).isEqualTo(REPLAY_STEPS.size() + 2);
        for (int index = 25; index < dates.size() - 1; index++) {
            assertThat(executions.get(dates.get(index))).isEqualTo(REPLAY_STEPS.size());
        }
        assertThat(executions.get(dates.get(dates.size() - 1)))
                .isEqualTo(REPLAY_STEPS.size() + 2);
        assertThat(fixture.runStore).hasSize(1);
        assertThat(fixture.stepStore).hasSize(3);
    }

    @Test
    void importsHistoricalEvidenceBeforeReplayingTheColdStartPlan() {
        Fixture fixture = fixture();
        LocalDate date = LocalDate.of(2026, 1, 9);
        LocalDate nextTradingDate = LocalDate.of(2026, 1, 12);
        AiHistoricalEvidenceImportService importer = mock(AiHistoricalEvidenceImportService.class);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                date, nextTradingDate, 120, 125, 200, List.of(date, nextTradingDate));
        AtomicBoolean imported = new AtomicBoolean(false);
        when(importer.importEvidence(any())).thenAnswer(invocation -> {
            imported.set(true);
            return new AiHistoricalEvidenceImportService.ImportResult(
                    2, 0, 200, "imported", List.of());
        });
        when(fixture.sourceService.load(any(), any())).thenAnswer(invocation -> {
            LocalDate tradeDate = invocation.getArgument(0);
            if (!imported.get()) {
                return new HistoricalUniverseSourceService.HistoricalDayEvidence(
                        "MISSING_HISTORICAL_UNIVERSE", tradeDate, tradeDate.atTime(16, 0),
                        null, null, 0, null, List.of("尚未导入"));
            }
            return ready(tradeDate, 1);
        });
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            return outcome(invocation.getArgument(0), context.tradeDate());
        });
        AiHistoricalBootstrapService service = new AiHistoricalBootstrapServiceImpl(
                fixture.runMapper, fixture.stepMapper, importer, fixture.sourceService,
                fixture.executor, new ObjectMapper().findAndRegisterModules());

        AiHistoricalBootstrapService.BootstrapResult result = service.run(
                new AiHistoricalBootstrapService.BootstrapRequest(
                        date, nextTradingDate, 11L, null, "HISTORICAL:IMPORTED",
                        LocalDateTime.of(2026, 7, 14, 10, 0), plan));

        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(importer).importEvidence(any());
        verify(fixture.sourceService, times(2)).load(date, date.atTime(16, 0));
        verify(fixture.sourceService, times(2)).load(
                nextTradingDate, nextTradingDate.atTime(16, 0));
        verify(fixture.sourceService, never()).load(
                LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 10).atTime(16, 0));
        verify(fixture.executor).execute(
                org.mockito.ArgumentMatchers.eq("MATURE_HISTORICAL_SAMPLE_LABELS"), any());
        verify(fixture.executor).execute(
                org.mockito.ArgumentMatchers.eq("EVALUATE_HISTORICAL_PREDICTIONS"), any());
    }

    @Test
    void reusesCompleteHistoricalEvidenceWithoutCallingExternalImporterAgain() {
        Fixture fixture = fixture();
        LocalDate date = LocalDate.of(2026, 1, 9);
        LocalDate nextTradingDate = LocalDate.of(2026, 1, 12);
        AiHistoricalEvidenceImportService importer = mock(AiHistoricalEvidenceImportService.class);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                date, nextTradingDate, 120, 125, 300, List.of(date, nextTradingDate));
        when(fixture.sourceService.load(any(), any())).thenAnswer(invocation -> {
            LocalDate tradeDate = invocation.getArgument(0);
            return ready(tradeDate, tradeDate.equals(date) ? 1 : 2);
        });
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            return outcome(invocation.getArgument(0), context.tradeDate());
        });
        AiHistoricalBootstrapService service = new AiHistoricalBootstrapServiceImpl(
                fixture.runMapper, fixture.stepMapper, importer, fixture.sourceService,
                fixture.executor, new ObjectMapper().findAndRegisterModules());

        AiHistoricalBootstrapService.BootstrapResult result = service.run(
                new AiHistoricalBootstrapService.BootstrapRequest(
                        date, nextTradingDate, 11L, null, "HISTORICAL:REUSED",
                        LocalDateTime.of(2026, 7, 14, 10, 0), plan));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.processedTradingDays()).isEqualTo(2);
        verify(importer, never()).importEvidence(any());
        verify(fixture.sourceService).load(date, date.atTime(16, 0));
        verify(fixture.sourceService).load(nextTradingDate, nextTradingDate.atTime(16, 0));
    }

    @Test
    void importsOnlyMissingDatesWhenExpandingExistingHistoricalEvidence() {
        Fixture fixture = fixture();
        LocalDate existingDate = LocalDate.of(2026, 1, 9);
        LocalDate missingDate = LocalDate.of(2026, 1, 12);
        LocalDate anotherMissingDate = LocalDate.of(2026, 1, 13);
        List<LocalDate> plannedDates = List.of(existingDate, missingDate, anotherMissingDate);
        AiHistoricalEvidenceImportService importer = mock(AiHistoricalEvidenceImportService.class);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                existingDate, anotherMissingDate, 180, 185, 300, plannedDates);
        AtomicBoolean imported = new AtomicBoolean(false);
        when(importer.importEvidence(any())).thenAnswer(invocation -> {
            imported.set(true);
            return new AiHistoricalEvidenceImportService.ImportResult(
                    2, 0, 300, "imported missing dates", List.of());
        });
        when(fixture.sourceService.load(any(), any())).thenAnswer(invocation -> {
            LocalDate tradeDate = invocation.getArgument(0);
            if (existingDate.equals(tradeDate) || imported.get()) {
                return ready(tradeDate, plannedDates.indexOf(tradeDate) + 1L);
            }
            return new HistoricalUniverseSourceService.HistoricalDayEvidence(
                    "MISSING_HISTORICAL_UNIVERSE", tradeDate, tradeDate.atTime(16, 0),
                    null, null, 0, null, List.of("尚未导入"));
        });
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            return outcome(invocation.getArgument(0), context.tradeDate());
        });
        AiHistoricalBootstrapService service = new AiHistoricalBootstrapServiceImpl(
                fixture.runMapper, fixture.stepMapper, importer, fixture.sourceService,
                fixture.executor, new ObjectMapper().findAndRegisterModules());

        AiHistoricalBootstrapService.BootstrapResult result = service.run(
                new AiHistoricalBootstrapService.BootstrapRequest(
                        existingDate, anotherMissingDate, 11L, null,
                        "HISTORICAL:PARTIAL-REUSE", LocalDateTime.of(2026, 7, 17, 10, 0), plan));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.processedTradingDays()).isEqualTo(3);
        ArgumentCaptor<AiHistoricalEvidenceImportService.ImportRequest> requestCaptor =
                ArgumentCaptor.forClass(AiHistoricalEvidenceImportService.ImportRequest.class);
        verify(importer).importEvidence(requestCaptor.capture());
        assertThat(requestCaptor.getValue().plan().tradingDates())
                .containsExactly(missingDate, anotherMissingDate);
        verify(fixture.sourceService, times(1)).load(existingDate, existingDate.atTime(16, 0));
        verify(fixture.sourceService, times(2)).load(missingDate, missingDate.atTime(16, 0));
        verify(fixture.sourceService, times(2)).load(
                anotherMissingDate, anotherMissingDate.atTime(16, 0));
    }

    @Test
    void updatesResearchAndTrainsCandidateAfterHistoricalReplay() {
        Fixture fixture = fixture();
        LocalDate date = LocalDate.of(2026, 1, 5);
        AiHistoricalEvidenceImportService importer = mock(AiHistoricalEvidenceImportService.class);
        AiWeeklyEvolutionRunner weeklyRunner = mock(AiWeeklyEvolutionRunner.class);
        AiMonthlyTrainingRunner trainingRunner = mock(AiMonthlyTrainingRunner.class);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                date, date, 120, 125, 200, List.of(date));
        when(importer.importEvidence(any())).thenReturn(new AiHistoricalEvidenceImportService.ImportResult(
                1, 0, 200, "imported", List.of()));
        when(weeklyRunner.run(any(), any())).thenReturn(
                new AiResearchCycleResult("SUCCESS", 10, 10, 0, "因子研究已更新"));
        when(trainingRunner.run(any(), any())).thenReturn(
                new AiResearchCycleResult("SUCCESS", 24000, 1, 0, "候选模型已生成"));
        when(fixture.sourceService.load(any(), any())).thenReturn(ready(date, 1));
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            return outcome(invocation.getArgument(0), context.tradeDate());
        });
        AiHistoricalBootstrapService service = new AiHistoricalBootstrapServiceImpl(
                fixture.runMapper, fixture.stepMapper, importer, fixture.sourceService,
                fixture.executor, weeklyRunner, trainingRunner,
                new ObjectMapper().findAndRegisterModules());

        AiHistoricalBootstrapService.BootstrapResult result = service.run(
                new AiHistoricalBootstrapService.BootstrapRequest(
                        date, date, 11L, null, "HISTORICAL:TRAIN", LocalDateTime.of(2026, 7, 14, 10, 0), plan));

        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(weeklyRunner).run(any(), any());
        verify(trainingRunner).run(any(), any());
    }

    @Test
    void truncatesLargeImportWarningsBeforeAdvancingToHistoricalTraining() {
        Fixture fixture = fixture();
        LocalDate date = LocalDate.of(2026, 1, 5);
        AiHistoricalEvidenceImportService importer = mock(AiHistoricalEvidenceImportService.class);
        AiWeeklyEvolutionRunner weeklyRunner = mock(AiWeeklyEvolutionRunner.class);
        AiMonthlyTrainingRunner trainingRunner = mock(AiMonthlyTrainingRunner.class);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                date, date, 120, 125, 240, List.of(date));
        List<String> warnings = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            warnings.add("688" + String.format("%03d", index % 300)
                    + "：腾讯历史 K 线返回为空：QFQ-" + index + "-" + "X".repeat(180));
        }
        when(importer.importEvidence(any())).thenReturn(new AiHistoricalEvidenceImportService.ImportResult(
                1, 0, 240, "imported", warnings));
        when(weeklyRunner.run(any(), any())).thenReturn(
                new AiResearchCycleResult("SUCCESS", 10, 10, 0, "因子研究已更新"));
        when(trainingRunner.run(any(), any())).thenReturn(
                new AiResearchCycleResult("SUCCESS", 24000, 1, 0, "候选模型已生成"));
        when(fixture.sourceService.load(any(), any())).thenReturn(ready(date, 1));
        when(fixture.runMapper.updateStateFenced(any(AiPipelineRun.class), anyString(), any())).thenAnswer(invocation -> {
            AiPipelineRun run = invocation.getArgument(0);
            if (run.errorMessage != null
                    && run.errorMessage.length() > AiHistoricalBootstrapServiceImpl.MAX_PIPELINE_MESSAGE_LENGTH) {
                throw new IllegalStateException("Data truncation");
            }
            return 1;
        });
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            return outcome(invocation.getArgument(0), context.tradeDate());
        });
        AiHistoricalBootstrapService service = new AiHistoricalBootstrapServiceImpl(
                fixture.runMapper, fixture.stepMapper, importer, fixture.sourceService,
                fixture.executor, weeklyRunner, trainingRunner,
                new ObjectMapper().findAndRegisterModules());

        AiHistoricalBootstrapService.BootstrapResult result = service.run(
                new AiHistoricalBootstrapService.BootstrapRequest(
                        date, date, 11L, null, "HISTORICAL:TRUNCATE-WARNINGS",
                        LocalDateTime.of(2026, 7, 18, 10, 0), plan));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.run().status).isEqualTo("SUCCESS");
        verify(weeklyRunner).run(any(), any());
        verify(trainingRunner).run(any(), any());
    }

    @Test
    void drainsHistoricalLabelsAndEvaluationsInBoundedBatches() {
        Fixture fixture = fixture();
        LocalDate date = LocalDate.of(2026, 1, 5);
        AtomicLong labelCalls = new AtomicLong();
        AtomicLong evaluationCalls = new AtomicLong();
        when(fixture.sourceService.load(any(), any())).thenReturn(ready(date, 1));
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            String step = invocation.getArgument(0);
            if ("MATURE_HISTORICAL_SAMPLE_LABELS".equals(step)) {
                long call = labelCalls.incrementAndGet();
                return batchOutcome(step, call == 1 ? 2_000 : 17);
            }
            if ("EVALUATE_HISTORICAL_PREDICTIONS".equals(step)) {
                long call = evaluationCalls.incrementAndGet();
                return batchOutcome(step, call == 1 ? 2_000 : 29);
            }
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            return outcome(step, context.tradeDate());
        });

        AiHistoricalBootstrapService.BootstrapResult result = service(fixture).run(request(date, date));

        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(fixture.executor, times(2)).execute(
                org.mockito.ArgumentMatchers.eq("MATURE_HISTORICAL_SAMPLE_LABELS"), any());
        verify(fixture.executor, times(2)).execute(
                org.mockito.ArgumentMatchers.eq("EVALUATE_HISTORICAL_PREDICTIONS"), any());
    }

    @Test
    void stopsDrainingWhenAFullBatchProducesNoNewMatureResults() {
        Fixture fixture = fixture();
        LocalDate date = LocalDate.of(2026, 1, 5);
        when(fixture.sourceService.load(any(), any())).thenReturn(ready(date, 1));
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            String step = invocation.getArgument(0);
            if ("MATURE_HISTORICAL_SAMPLE_LABELS".equals(step)) {
                return new AiGlobalDailyResearchExecutor.StepOutcome(
                        "SUCCESS", 2_000, 0, 0,
                        "{\"processed\":2000}", "labels-no-progress",
                        List.of(), null, null);
            }
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            return outcome(step, context.tradeDate());
        });

        AiHistoricalBootstrapService.BootstrapResult result = service(fixture).run(request(date, date));

        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(fixture.executor, times(1)).execute(
                org.mockito.ArgumentMatchers.eq("MATURE_HISTORICAL_SAMPLE_LABELS"), any());
    }

    @Test
    void continuesTrainingWithPartialSuccessWhenTailLabelsHaveSourceWarnings() {
        Fixture fixture = fixture();
        LocalDate date = LocalDate.of(2026, 1, 5);
        AiHistoricalEvidenceImportService importer = mock(AiHistoricalEvidenceImportService.class);
        AiWeeklyEvolutionRunner weeklyRunner = mock(AiWeeklyEvolutionRunner.class);
        AiMonthlyTrainingRunner trainingRunner = mock(AiMonthlyTrainingRunner.class);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                date, date, 120, 125, 300, List.of(date));
        when(importer.importEvidence(any())).thenReturn(new AiHistoricalEvidenceImportService.ImportResult(
                1, 0, 300, "imported", List.of()));
        when(fixture.sourceService.load(any(), any())).thenReturn(ready(date, 1));
        when(weeklyRunner.run(any(), any())).thenReturn(
                new AiResearchCycleResult("SUCCESS", 10, 10, 0, "研究完成"));
        when(trainingRunner.run(any(), any())).thenReturn(
                new AiResearchCycleResult("SUCCESS", 24000, 1, 0, "训练完成"));
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            String step = invocation.getArgument(0);
            if ("MATURE_HISTORICAL_SAMPLE_LABELS".equals(step)) {
                return new AiGlobalDailyResearchExecutor.StepOutcome(
                        "SUCCESS_WITH_WARNINGS", 17, 64, 1,
                        "{\"processed\":17}", "labels-warning",
                        List.of("002594：K线暂不可用"), null, null);
            }
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            return outcome(step, context.tradeDate());
        });
        AiHistoricalBootstrapService service = new AiHistoricalBootstrapServiceImpl(
                fixture.runMapper, fixture.stepMapper, importer, fixture.sourceService,
                fixture.executor, weeklyRunner, trainingRunner,
                new ObjectMapper().findAndRegisterModules());

        AiHistoricalBootstrapService.BootstrapResult result = service.run(
                new AiHistoricalBootstrapService.BootstrapRequest(
                        date, date, 11L, null, "HISTORICAL:TAIL-WARNING",
                        LocalDateTime.of(2026, 7, 14, 10, 0), plan));

        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.errors()).containsExactly(
                "MATURE_HISTORICAL_SAMPLE_LABELS：002594：K线暂不可用");
        verify(fixture.executor).execute(
                org.mockito.ArgumentMatchers.eq("EVALUATE_HISTORICAL_PREDICTIONS"), any());
        verify(weeklyRunner).run(any(), any());
        verify(trainingRunner).run(any(), any());
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

    private static AiGlobalDailyResearchExecutor.StepOutcome batchOutcome(String step, int processed) {
        return new AiGlobalDailyResearchExecutor.StepOutcome(
                "SUCCESS", processed, processed, 0,
                "{\"processed\":" + processed + "}", step + "-" + processed,
                List.of(), null, null);
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
