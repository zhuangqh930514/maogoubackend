package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillShard;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import com.maogou.stock.service.research.HistoricalBackfillShardExecutor;
import com.maogou.stock.service.research.HistoricalReadinessEvaluator;
import com.maogou.stock.service.research.HistoricalUniverseSourceService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalBackfillShardExecutorImplTest {

    @Test
    void replayResumesFromCheckpointAndWritesAfterEachDate() throws Exception {
        AiHistoricalEvidenceImportService evidenceImport = mock(AiHistoricalEvidenceImportService.class);
        HistoricalUniverseSourceService source = mock(HistoricalUniverseSourceService.class);
        AiGlobalDailyResearchExecutor executor = mock(AiGlobalDailyResearchExecutor.class);
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3));
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                dates.get(0), dates.get(2), 3, 10, 200, dates);
        AiHistoricalBackfillShard shard = shard("REPLAY_BLOCK", 1, dates.get(0));
        shard.checkpointJson = "{\"completedDates\":[\"2026-07-01\"]}";
        when(source.load(any(), any())).thenAnswer(invocation -> {
            LocalDate tradeDate = invocation.getArgument(0);
            return new HistoricalUniverseSourceService.HistoricalDayEvidence(
                    "READY", tradeDate, tradeDate.atTime(16, 0), 10L, 11L, 200,
                    "evidence-fingerprint-" + tradeDate, List.of());
        });
        when(executor.execute(any(), any())).thenReturn(successOutcome());
        List<String> checkpoints = new ArrayList<>();
        HistoricalBackfillShardExecutorImpl service = new HistoricalBackfillShardExecutorImpl(
                evidenceImport, source, executor, new ObjectMapper().findAndRegisterModules());

        HistoricalBackfillShardExecutor.ExecutionResult result = service.execute(
                new HistoricalBackfillShardExecutor.ExecutionCommand(
                        1L, shard, plan, 10L, 11L, "run-key", LocalDateTime.of(2026, 7, 4, 10, 0),
                        1, () -> { }, (checkpoint, output, rejected) -> checkpoints.add(checkpoint)));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(checkpoints).hasSize(2);
        assertThat(checkpoints.get(0)).contains("2026-07-02");
        assertThat(checkpoints.get(1)).contains("2026-07-03");
    }

    @Test
    void importsOnlyTheShardBlockAndPreservesGlobalWarmupWindow() {
        AiHistoricalEvidenceImportService evidenceImport = mock(AiHistoricalEvidenceImportService.class);
        HistoricalUniverseSourceService source = mock(HistoricalUniverseSourceService.class);
        AiGlobalDailyResearchExecutor executor = mock(AiGlobalDailyResearchExecutor.class);
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3));
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                dates.get(0), dates.get(2), 3, 180, 200, dates);
        AiHistoricalBackfillShard shard = shard("IMPORT_HISTORICAL_EVIDENCE", 1, dates.get(0));
        when(evidenceImport.importEvidence(any())).thenReturn(new AiHistoricalEvidenceImportService.ImportResult(
                3, 0, 200, "source-fingerprint", List.of()));
        HistoricalBackfillShardExecutorImpl service = new HistoricalBackfillShardExecutorImpl(
                evidenceImport, source, executor, new ObjectMapper().findAndRegisterModules());

        HistoricalBackfillShardExecutor.ExecutionResult result = service.execute(
                new HistoricalBackfillShardExecutor.ExecutionCommand(
                        2L, shard, plan, 10L, null, "run-key", LocalDateTime.of(2026, 7, 4, 10, 0),
                        1, () -> { }, (checkpoint, output, rejected) -> { }));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.successCount()).isEqualTo(3);
        org.mockito.ArgumentCaptor<AiHistoricalEvidenceImportService.ImportRequest> captor =
                org.mockito.ArgumentCaptor.forClass(AiHistoricalEvidenceImportService.ImportRequest.class);
        org.mockito.Mockito.verify(evidenceImport).importEvidence(captor.capture());
        assertThat(captor.getValue().plan().replayTradingDays()).isEqualTo(180);
    }

    @Test
    void readinessBlockCannotBeReportedAsSuccessfulWork() {
        AiHistoricalEvidenceImportService evidenceImport = mock(AiHistoricalEvidenceImportService.class);
        HistoricalUniverseSourceService source = mock(HistoricalUniverseSourceService.class);
        AiGlobalDailyResearchExecutor executor = mock(AiGlobalDailyResearchExecutor.class);
        HistoricalReadinessEvaluator readiness = mock(HistoricalReadinessEvaluator.class);
        LocalDate tradeDate = LocalDate.of(2026, 7, 3);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = new AiHistoricalEvidenceImportService.ColdStartPlan(
                tradeDate, tradeDate, 1, 180, 200, List.of(tradeDate));
        AiHistoricalBackfillShard shard = shard("READINESS_CHECK", 0, null);
        when(readiness.evaluate(any())).thenReturn(new HistoricalReadinessEvaluator.Evaluation(
                "BLOCKED_BY_QUALITY", "R1_HISTORICAL_FACTS_READY", 120, 200,
                java.util.Map.of(1, 20_000), java.util.Map.of("UP", 20),
                100, 90, java.math.BigDecimal.valueOf(0.90),
                100, 98, java.math.BigDecimal.valueOf(0.98),
                100, 99, java.math.BigDecimal.valueOf(0.99),
                java.util.Map.of(), java.util.Map.of(), 1, 0, 0, 0, 0,
                List.of("PIT_VIOLATION=1"), "evidence-checksum"));

        HistoricalBackfillShardExecutorImpl service = new HistoricalBackfillShardExecutorImpl(
                evidenceImport, source, executor, readiness, new ObjectMapper().findAndRegisterModules());

        HistoricalBackfillShardExecutor.ExecutionResult result = service.execute(
                new HistoricalBackfillShardExecutor.ExecutionCommand(
                        3L, shard, plan, 10L, 11L, "run-key", LocalDateTime.of(2026, 7, 4, 10, 0),
                        1, () -> { }, (checkpoint, output, rejected) -> { }));

        assertThat(result.status()).isEqualTo("BLOCKED_BY_QUALITY");
        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.errorCode()).isEqualTo("HISTORICAL_READINESS_BLOCKED");
        assertThat(result.errorDetail()).contains("PIT_VIOLATION=1");
    }

    private static AiHistoricalBackfillShard shard(String stage, int bucket, LocalDate date) {
        AiHistoricalBackfillShard value = new AiHistoricalBackfillShard();
        value.id = 99L;
        value.backfillRunId = 1L;
        value.stageKey = stage;
        value.bucketNo = bucket;
        value.tradeDate = date;
        value.status = "RUNNING";
        value.attemptNo = 1;
        value.maxAttempts = 5;
        return value;
    }

    private static AiGlobalDailyResearchExecutor.StepOutcome successOutcome() {
        return new AiGlobalDailyResearchExecutor.StepOutcome(
                "SUCCESS", 1, 1, 0, "{\"checkpoint\":true}", "fingerprint", List.of(), 11L, null);
    }
}
