package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiGlobalDailyResearchServiceImplTest {

    private static final List<String> EXPECTED_STEPS = List.of(
            "SNAPSHOT_UNIVERSE",
            "FETCH_SOURCE_DATA",
            "WAIT_DATA_READY",
            "BUILD_SAMPLES",
            "MATURE_SAMPLE_LABELS",
            "COMPUTE_FACTORS",
            "GENERATE_PREDICTIONS",
            "EVALUATE_PREDICTIONS"
    );

    @Test
    void executesExactlyTheEightGlobalResearchStepsInOrder() {
        Fixture fixture = fixture();
        List<String> executed = new ArrayList<>();
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            executed.add(key);
            Long batchId = "FETCH_SOURCE_DATA".equals(key) ? 55L : null;
            return success(key, batchId);
        });

        AiGlobalDailyResearchService.PipelineResult result = service(fixture).run(request());

        assertThat(AiGlobalDailyResearchServiceImpl.DAILY_STEPS).containsExactlyElementsOf(EXPECTED_STEPS);
        assertThat(executed).containsExactlyElementsOf(EXPECTED_STEPS);
        assertThat(result.run().scopeType).isEqualTo("GLOBAL");
        assertThat(result.run().ownerUserId).isNull();
        assertThat(result.run().dataBatchId).isEqualTo(55L);
        assertThat(result.run().status).isEqualTo("SUCCESS");
        assertThat(result.steps()).extracting(step -> step.status).containsOnly("SUCCESS");
    }

    @Test
    void waitingSourcePersistsRetryAndResumesSameRunWithANewImmutableBatchRevision() {
        Fixture fixture = fixture();
        Map<String, Integer> calls = new LinkedHashMap<>();
        List<Integer> fetchAttempts = new ArrayList<>();
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            AiGlobalDailyResearchExecutor.PipelineContext context = invocation.getArgument(1);
            calls.merge(key, 1, Integer::sum);
            if ("SNAPSHOT_UNIVERSE".equals(key)) {
                return outcome("SUCCESS", key, "{\"universeSnapshotId\":91}", null, null);
            }
            if ("FETCH_SOURCE_DATA".equals(key)) {
                fetchAttempts.add(context.attemptNo());
                long batchId = context.attemptNo() == 0 ? 55L : 56L;
                return outcome("SUCCESS", key,
                        "{\"universeSnapshotId\":91,\"dataBatchId\":" + batchId + "}",
                        batchId, null);
            }
            if ("WAIT_DATA_READY".equals(key) && calls.get(key) == 1) {
                return outcome("WAITING_SOURCE", key,
                        "{\"universeSnapshotId\":91,\"dataBatchId\":55,\"missing\":2}",
                        55L, LocalDateTime.of(2026, 7, 14, 16, 10));
            }
            return success(key, null);
        });

        AiGlobalDailyResearchService.PipelineResult waiting = service(fixture).run(request());

        assertThat(waiting.run().status).isEqualTo("WAITING_SOURCE");
        assertThat(waiting.run().nextRetryAt).isEqualTo(LocalDateTime.of(2026, 7, 14, 16, 10));
        assertThat(waiting.run().dataBatchId).isEqualTo(55L);
        assertThat(waiting.steps()).filteredOn(step -> "WAIT_DATA_READY".equals(step.stepKey))
                .singleElement().satisfies(step -> {
                    assertThat(step.status).isEqualTo("WAITING_SOURCE");
                    assertThat(step.nextRetryAt).isEqualTo(LocalDateTime.of(2026, 7, 14, 16, 10));
                    assertThat(step.checkpointJson).contains("\"universeSnapshotId\":91", "\"dataBatchId\":55");
                });
        assertThat(calls).doesNotContainKeys("BUILD_SAMPLES", "GENERATE_PREDICTIONS");

        AiGlobalDailyResearchService restartedService = service(fixture);
        AiGlobalDailyResearchService.PipelineResult completed = restartedService.run(request());

        assertThat(completed.run().id).isEqualTo(waiting.run().id);
        assertThat(completed.run().dataBatchId).isEqualTo(56L);
        assertThat(completed.run().status).isEqualTo("SUCCESS");
        assertThat(fetchAttempts).containsExactly(0, 1);
        assertThat(calls.get("SNAPSHOT_UNIVERSE")).isEqualTo(1);
        assertThat(calls.get("FETCH_SOURCE_DATA")).isEqualTo(2);
        assertThat(calls.get("WAIT_DATA_READY")).isEqualTo(2);
        assertThat(calls.get("BUILD_SAMPLES")).isEqualTo(1);
    }

    @Test
    void failedStepResumesWithoutRepeatingCompletedImmutableSteps() {
        Fixture fixture = fixture();
        Map<String, Integer> calls = new LinkedHashMap<>();
        when(fixture.executor.execute(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            int call = calls.merge(key, 1, Integer::sum);
            if ("COMPUTE_FACTORS".equals(key) && call == 1) {
                throw new IllegalStateException("因子存储暂时不可用");
            }
            return success(key, "FETCH_SOURCE_DATA".equals(key) ? 55L : null);
        });

        AiGlobalDailyResearchService.PipelineResult failed = service(fixture).run(request());
        assertThat(failed.run().status).isEqualTo("FAILED");
        assertThat(failed.run().currentStep).isEqualTo("COMPUTE_FACTORS");

        AiGlobalDailyResearchService.PipelineResult recovered = service(fixture).run(request());

        assertThat(recovered.run().status).isEqualTo("SUCCESS");
        assertThat(calls.get("SNAPSHOT_UNIVERSE")).isEqualTo(1);
        assertThat(calls.get("BUILD_SAMPLES")).isEqualTo(1);
        assertThat(calls.get("COMPUTE_FACTORS")).isEqualTo(2);
        assertThat(recovered.steps()).filteredOn(step -> "COMPUTE_FACTORS".equals(step.stepKey))
                .singleElement().extracting(step -> step.retryCount).isEqualTo(1);
    }

    @Test
    void refusesConcurrentWorkerWhenGlobalLeaseCannotBeClaimed() {
        Fixture fixture = fixture();
        when(fixture.runMapper.claimExecution(anyLong(), anyString(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service(fixture).run(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("其他实例");
        verify(fixture.executor, never()).execute(anyString(), any());
    }

    private static AiGlobalDailyResearchExecutor.StepOutcome success(String key, Long dataBatchId) {
        return outcome("SUCCESS", key, "{\"step\":\"" + key + "\"}", dataBatchId, null);
    }

    private static AiGlobalDailyResearchExecutor.StepOutcome outcome(
            String status,
            String key,
            String checkpoint,
            Long dataBatchId,
            LocalDateTime nextRetryAt
    ) {
        return new AiGlobalDailyResearchExecutor.StepOutcome(
                status, 3, 3, 0, checkpoint, "output-" + key,
                List.of(), dataBatchId, nextRetryAt);
    }

    private static AiGlobalDailyResearchService service(Fixture fixture) {
        return new AiGlobalDailyResearchServiceImpl(
                fixture.runMapper, fixture.stepMapper, fixture.executor,
                Duration.ofMinutes(2), Duration.ofSeconds(20));
    }

    private static AiGlobalDailyResearchService.PipelineRequest request() {
        return new AiGlobalDailyResearchService.PipelineRequest(
                LocalDate.of(2026, 7, 14), 1L, null,
                "GLOBAL_DAILY:2026-07-14", "global-input-fingerprint",
                LocalDateTime.of(2026, 7, 14, 16, 0));
    }

    private static Fixture fixture() {
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiPipelineStepMapper stepMapper = mock(AiPipelineStepMapper.class);
        AiGlobalDailyResearchExecutor executor = mock(AiGlobalDailyResearchExecutor.class);
        AtomicLong ids = new AtomicLong(4000);
        List<AiPipelineRun> runs = new ArrayList<>();
        List<AiPipelineStep> steps = new ArrayList<>();

        when(runMapper.insertIgnore(any())).thenAnswer(invocation -> {
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
        when(runMapper.updateStateFenced(any(), anyString(), any())).thenReturn(1);
        when(runMapper.releaseExecution(anyLong(), anyString(), any())).thenReturn(1);

        when(stepMapper.insertIgnore(any())).thenAnswer(invocation -> {
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
                .sorted(Comparator.comparing(step -> step.stepOrder)).toList());
        when(stepMapper.updateStateFenced(any(), anyString(), any())).thenReturn(1);

        return new Fixture(runMapper, stepMapper, executor);
    }

    private record Fixture(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            AiGlobalDailyResearchExecutor executor
    ) {
    }
}
