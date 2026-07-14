package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiGlobalDailyResearchServiceImplTest {

    @Test
    void executesOnlyTheNineDailyStepsInTheRequiredOrder() {
        Fixture fixture = fixture();
        List<String> executed = new ArrayList<>();
        when(fixture.executor.execute(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(AiGlobalDailyResearchExecutor.PipelineContext.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    executed.add(key);
                    return new AiGlobalDailyResearchExecutor.StepOutcome(
                            3, 3, 0, "{\"step\":\"" + key + "\"}", "output-" + key, List.of());
                });
        AiGlobalDailyResearchService service = service(fixture);

        AiGlobalDailyResearchService.PipelineResult result = service.run(request());

        assertThat(executed).containsExactly(
                "FETCH_DATA",
                "CHECK_DATA_QUALITY",
                "BUILD_SAMPLES",
                "VERIFY_LABELS",
                "COMPUTE_FACTORS",
                "GENERATE_PREDICTIONS",
                "GENERATE_REPORTS");
        assertThat(result.steps()).extracting(item -> item.stepKey).containsExactlyElementsOf(
                AiGlobalDailyResearchServiceImpl.DAILY_STEPS);
        assertThat(executed).doesNotContain("WEEKLY_EXPERIMENT", "WEEKLY_BACKTEST", "MONTHLY_TRAINING");
        assertThat(result.run().status).isEqualTo("SUCCESS");
        assertThat(result.steps()).extracting(item -> item.status).containsOnly("SUCCESS");
        assertThat(result.steps()).extracting(item -> item.outputFingerprint)
                .allMatch(value -> value != null && !value.isBlank());
        verify(fixture.executor).buildResearchDailyReport(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("SUCCESS"),
                org.mockito.ArgumentMatchers.isNull());
        verify(fixture.executor).buildDailyInsight(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("SUCCESS"),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void resumesFromTheFailedStepWithoutRepeatingSuccessfulArtifacts() {
        Fixture fixture = fixture();
        Map<String, Integer> calls = new LinkedHashMap<>();
        AtomicBoolean firstReportAttempt = new AtomicBoolean(true);
        when(fixture.executor.execute(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(AiGlobalDailyResearchExecutor.PipelineContext.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    calls.merge(key, 1, Integer::sum);
                    if ("GENERATE_REPORTS".equals(key) && firstReportAttempt.getAndSet(false)) {
                        throw new IllegalStateException("模型服务暂时不可用");
                    }
                    return success(key);
                });
        AiGlobalDailyResearchService service = service(fixture);

        AiGlobalDailyResearchService.PipelineResult failed = service.run(request());
        assertThat(failed.run().status).isEqualTo("FAILED");
        assertThat(failed.run().currentStep).isEqualTo("GENERATE_REPORTS");

        AiGlobalDailyResearchService.PipelineResult recovered = service.run(request());

        assertThat(recovered.run().status).isEqualTo("SUCCESS");
        assertThat(calls.get("FETCH_DATA")).isEqualTo(1);
        assertThat(calls.get("GENERATE_PREDICTIONS")).isEqualTo(1);
        assertThat(calls.get("GENERATE_REPORTS")).isEqualTo(2);
        verify(fixture.executor).buildDailyInsight(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("SUCCESS"),
                org.mockito.ArgumentMatchers.isNull());
        verify(fixture.executor).buildResearchDailyReport(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("SUCCESS"),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(recovered.steps().stream()
                .filter(item -> "GENERATE_REPORTS".equals(item.stepKey))
                .findFirst().orElseThrow().retryCount).isEqualTo(1);
        verify(fixture.reportService).generate(org.mockito.ArgumentMatchers.argThat(request ->
                "FAILED".equals(request.pipelineStatus())
                        && "GENERATE_REPORTS".equals(request.failedStep())
                        && request.idempotencyKey().contains("FAILED:GENERATE_REPORTS")));
        verify(fixture.executor).onPipelineFailure(
                org.mockito.ArgumentMatchers.any(AiGlobalDailyResearchExecutor.PipelineContext.class),
                org.mockito.ArgumentMatchers.eq("GENERATE_REPORTS"),
                org.mockito.ArgumentMatchers.contains("模型服务暂时不可用"));
    }

    @Test
    void continuesAfterSingleStockFailuresAndFinishesWithWarnings() {
        Fixture fixture = fixture();
        List<String> executed = new ArrayList<>();
        when(fixture.executor.execute(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(AiGlobalDailyResearchExecutor.PipelineContext.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    executed.add(key);
                    if ("GENERATE_REPORTS".equals(key)) {
                        return new AiGlobalDailyResearchExecutor.StepOutcome(
                                3, 2, 1, "{\"lastStock\":\"600519\"}",
                                "output-" + key, List.of("000001 分析失败"));
                    }
                    return success(key);
                });
        AiGlobalDailyResearchService service = service(fixture);

        AiGlobalDailyResearchService.PipelineResult result = service.run(request());

        assertThat(result.run().status).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.run().failedCount).isEqualTo(1);
        assertThat(executed).doesNotContain("BUILD_DAILY_INSIGHT");
        AiPipelineStep warningStep = result.steps().stream()
                .filter(item -> "GENERATE_REPORTS".equals(item.stepKey))
                .findFirst().orElseThrow();
        assertThat(warningStep.status).isEqualTo("SUCCESS_WITH_WARNINGS");
        assertThat(warningStep.checkpointJson).contains("600519");
        assertThat(warningStep.errorMessage).contains("000001");
        verify(fixture.executor).buildResearchDailyReport(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("PARTIAL_SUCCESS"),
                org.mockito.ArgumentMatchers.contains("000001"));
        verify(fixture.executor).buildDailyInsight(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("PARTIAL_SUCCESS"),
                org.mockito.ArgumentMatchers.contains("000001"));
        verify(fixture.executor, never()).onPipelineFailure(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reportGenerationFailureKeepsThePipelineRecoverableAtTheReportStep() {
        Fixture fixture = fixture();
        Map<String, Integer> calls = new LinkedHashMap<>();
        when(fixture.executor.execute(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(AiGlobalDailyResearchExecutor.PipelineContext.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    calls.merge(key, 1, Integer::sum);
                    return success(key);
                });
        when(fixture.executor.buildResearchDailyReport(
                org.mockito.ArgumentMatchers.any(AiGlobalDailyResearchExecutor.PipelineContext.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenThrow(new IllegalStateException("日报落库失败"))
                .thenReturn(success("BUILD_RESEARCH_DAILY_REPORT"));
        AiGlobalDailyResearchService service = service(fixture);

        AiGlobalDailyResearchService.PipelineResult failed = service.run(request());
        assertThat(failed.run().status).isEqualTo("FAILED");
        assertThat(failed.run().currentStep).isEqualTo("BUILD_RESEARCH_DAILY_REPORT");

        AiGlobalDailyResearchService.PipelineResult recovered = service.run(request());

        assertThat(recovered.run().status).isEqualTo("SUCCESS");
        assertThat(calls.get("FETCH_DATA")).isEqualTo(1);
        assertThat(calls).doesNotContainKey("BUILD_RESEARCH_DAILY_REPORT");
        verify(fixture.executor, times(2)).buildResearchDailyReport(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void rejectsAConcurrentRunnerWhenTheDatabaseLeaseCannotBeClaimed() {
        Fixture fixture = fixture();
        when(fixture.runMapper.claimExecution(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        AiGlobalDailyResearchService service = service(fixture);

        assertThatThrownBy(() -> service.run(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正在由其他实例执行");
        verify(fixture.executor, never()).execute(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void releasesTheDatabaseLeaseWhenStepStatePersistenceFails() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("step storage unavailable"))
                .when(fixture.stepMapper)
                .updateStateFenced(
                        org.mockito.ArgumentMatchers.any(AiPipelineStep.class),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        AiGlobalDailyResearchService service = service(fixture);

        assertThatThrownBy(() -> service.run(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step storage unavailable");

        verify(fixture.runMapper).releaseExecution(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleOwnerCannotWriteStepStateAfterFencingRejectsIt() {
        Fixture fixture = fixture();
        when(fixture.stepMapper.updateStateFenced(
                org.mockito.ArgumentMatchers.any(AiPipelineStep.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(0);
        AiGlobalDailyResearchService service = service(fixture);

        assertThatThrownBy(() -> service.run(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝覆盖步骤状态");

        verify(fixture.executor, never()).execute(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(fixture.stepMapper, never()).updateById(
                org.mockito.ArgumentMatchers.any(AiPipelineStep.class));
        verify(fixture.runMapper, never()).updateById(
                org.mockito.ArgumentMatchers.any(AiPipelineRun.class));
    }

    @Test
    void renewsLeaseInBackgroundWhileAnExternalStepIsBlocked() {
        Fixture fixture = fixture();
        AtomicBoolean heartbeatObserved = new AtomicBoolean(false);
        when(fixture.runMapper.renewExecution(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                    if (Thread.currentThread().getName().contains("lease-heartbeat")) {
                        heartbeatObserved.set(true);
                    }
                    return 1;
                });
        AtomicBoolean delayed = new AtomicBoolean(false);
        when(fixture.executor.execute(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                    if (delayed.compareAndSet(false, true)) {
                        Thread.sleep(80L);
                    }
                    return success(invocation.getArgument(0));
                });
        AiGlobalDailyResearchService service = new AiGlobalDailyResearchServiceImpl(
                fixture.runMapper, fixture.stepMapper, fixture.executor, fixture.reportService,
                Duration.ofMillis(250), Duration.ofMillis(10));

        AiGlobalDailyResearchService.PipelineResult result = service.run(request());

        assertThat(result.run().status).isEqualTo("SUCCESS");
        assertThat(heartbeatObserved).isTrue();
        verify(fixture.runMapper, atLeastOnce()).renewExecution(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static AiGlobalDailyResearchExecutor.StepOutcome success(String key) {
        return new AiGlobalDailyResearchExecutor.StepOutcome(
                3, 3, 0, "{\"step\":\"" + key + "\"}", "output-" + key, List.of());
    }

    private static AiGlobalDailyResearchService service(Fixture fixture) {
        return new AiGlobalDailyResearchServiceImpl(
                fixture.runMapper, fixture.stepMapper, fixture.executor, fixture.reportService);
    }

    private static AiGlobalDailyResearchService.PipelineRequest request() {
        return new AiGlobalDailyResearchService.PipelineRequest(
                5L, LocalDate.of(2026, 7, 10), 11L, 21L, null,
                "AUTO_CLOSE:2026-07-10", "daily-input-fingerprint",
                LocalDateTime.of(2026, 7, 10, 16, 0));
    }

    private static Fixture fixture() {
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiPipelineStepMapper stepMapper = mock(AiPipelineStepMapper.class);
        AiGlobalDailyResearchExecutor executor = mock(AiGlobalDailyResearchExecutor.class);
        AiResearchDailyReportService reportService = mock(AiResearchDailyReportService.class);
        AtomicLong ids = new AtomicLong(4000);
        List<AiPipelineRun> runs = new ArrayList<>();
        List<AiPipelineStep> steps = new ArrayList<>();
        when(reportService.generate(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            AiResearchDailyReportService.GenerationRequest request = invocation.getArgument(0);
            return new AiResearchDailyReportService.ReportView(
                    9000L,
                    request.tradeDate(),
                    1,
                    request.pipelineRunId(),
                    request.strategyReleaseId(),
                    request.modelVersionId(),
                    null,
                    true,
                    request.pipelineStatus(),
                    "日报",
                    "摘要",
                    "BALANCED",
                    0,
                    0,
                    0,
                    0,
                    "REALTIME",
                    java.math.BigDecimal.ZERO,
                    new com.maogou.stock.dto.ai.AiResearchDailyReportPayloads.ReportContent(
                            new com.maogou.stock.dto.ai.AiResearchDailyReportPayloads.Freshness(
                                    "REALTIME", java.math.BigDecimal.ZERO, null, null, request.generatedAt()),
                            new com.maogou.stock.dto.ai.AiResearchDailyReportPayloads.PipelineSummary(
                                    request.pipelineRunId(), request.pipelineStatus(), request.failedStep(), request.failedStep(),
                                    0, 0, 0, request.pipelineMessage(), List.of()),
                            null,
                            List.of(), List.of(), List.of(), List.of(), List.of(), null),
                    "# 日报",
                    request.generatedAt());
        });
        when(executor.buildResearchDailyReport(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenAnswer(invocation -> success("BUILD_RESEARCH_DAILY_REPORT"));
        when(executor.buildDailyInsight(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenAnswer(invocation -> success("BUILD_DAILY_INSIGHT"));
        when(runMapper.insertIgnore(org.mockito.ArgumentMatchers.any(AiPipelineRun.class)))
                .thenAnswer(invocation -> {
                    AiPipelineRun item = invocation.getArgument(0);
                    boolean exists = runs.stream().anyMatch(existing -> existing.userId.equals(item.userId)
                            && existing.idempotencyKey.equals(item.idempotencyKey));
                    if (!exists) {
                        item.id = ids.incrementAndGet();
                        runs.add(item);
                    }
                    return 1;
                });
        when(runMapper.selectByIdempotencyForUpdate(anyLong(), anyString()))
                .thenAnswer(invocation -> runs.stream().filter(item ->
                                item.userId.equals(invocation.getArgument(0))
                                        && item.idempotencyKey.equals(invocation.getArgument(1)))
                        .findFirst().orElse(null));
        when(runMapper.updateById(org.mockito.ArgumentMatchers.any(AiPipelineRun.class))).thenReturn(1);
        when(runMapper.updateStateFenced(
                org.mockito.ArgumentMatchers.any(AiPipelineRun.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(runMapper.claimExecution(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(runMapper.renewExecution(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(stepMapper.insertIgnore(org.mockito.ArgumentMatchers.any(AiPipelineStep.class)))
                .thenAnswer(invocation -> {
                    AiPipelineStep item = invocation.getArgument(0);
                    boolean exists = steps.stream().anyMatch(existing ->
                            existing.pipelineRunId.equals(item.pipelineRunId)
                                    && existing.stepKey.equals(item.stepKey));
                    if (!exists) {
                        item.id = ids.incrementAndGet();
                        steps.add(item);
                    }
                    return 1;
                });
        when(stepMapper.selectByRunIdForUpdate(anyLong())).thenAnswer(invocation -> steps.stream()
                .filter(item -> item.pipelineRunId.equals(invocation.getArgument(0)))
                .sorted(java.util.Comparator.comparing(item -> item.stepOrder)).toList());
        when(stepMapper.updateById(org.mockito.ArgumentMatchers.any(AiPipelineStep.class))).thenReturn(1);
        when(stepMapper.updateStateFenced(
                org.mockito.ArgumentMatchers.any(AiPipelineStep.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        return new Fixture(runMapper, stepMapper, executor, reportService);
    }

    private record Fixture(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            AiGlobalDailyResearchExecutor executor,
            AiResearchDailyReportService reportService
    ) {
    }
}
