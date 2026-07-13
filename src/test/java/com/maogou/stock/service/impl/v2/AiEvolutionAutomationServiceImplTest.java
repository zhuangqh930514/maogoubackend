package com.maogou.stock.service.impl.v2;

import com.maogou.stock.domain.entity.AiLearningJobLog;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.service.v2.AiEvolutionAutomationService;
import com.maogou.stock.service.v2.AiMonthlyTrainingRunner;
import com.maogou.stock.service.v2.AiWeeklyEvolutionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEvolutionAutomationServiceImplTest {

    @Test
    void weeklyCycleRunsOnlyEnabledUsersAndPersistsExplainableJobResults() {
        AiModelConfigMapper configMapper = mock(AiModelConfigMapper.class);
        AiLearningJobLogMapper jobLogMapper = mock(AiLearningJobLogMapper.class);
        AiWeeklyEvolutionRunner weeklyRunner = mock(AiWeeklyEvolutionRunner.class);
        AiMonthlyTrainingRunner monthlyRunner = mock(AiMonthlyTrainingRunner.class);
        AiModelConfig first = config(5L);
        AiModelConfig second = config(7L);
        when(configMapper.selectList(any())).thenReturn(List.of(first, second));
        when(weeklyRunner.run(any(), any())).thenReturn(
                new AiEvolutionAutomationService.CycleResult("SUCCESS", 2, 2, 0, "done"));
        when(jobLogMapper.insert(any(AiLearningJobLog.class))).thenAnswer(invocation -> {
            ((AiLearningJobLog) invocation.getArgument(0)).id = 100L;
            return 1;
        });
        AiEvolutionAutomationService service = new AiEvolutionAutomationServiceImpl(
                configMapper, jobLogMapper, weeklyRunner, monthlyRunner);

        service.runWeeklyForEnabledUsers();

        verify(weeklyRunner).run(org.mockito.ArgumentMatchers.eq(5L), any());
        verify(weeklyRunner).run(org.mockito.ArgumentMatchers.eq(7L), any());
        ArgumentCaptor<AiLearningJobLog> jobs = ArgumentCaptor.forClass(AiLearningJobLog.class);
        verify(jobLogMapper, times(2)).updateById(jobs.capture());
        assertThat(jobs.getAllValues()).allSatisfy(job -> {
            assertThat(job.jobType).isEqualTo("AI_V2_WEEKLY_EVOLUTION");
            assertThat(job.status).isEqualTo("SUCCESS");
            assertThat(job.processedCount).isEqualTo(2);
            assertThat(job.finishedAt).isNotNull();
        });
    }

    @Test
    void runnerFailureIsIsolatedAndRecordedInsteadOfStoppingOtherUsers() {
        AiModelConfigMapper configMapper = mock(AiModelConfigMapper.class);
        AiLearningJobLogMapper jobLogMapper = mock(AiLearningJobLogMapper.class);
        AiWeeklyEvolutionRunner weeklyRunner = mock(AiWeeklyEvolutionRunner.class);
        AiMonthlyTrainingRunner monthlyRunner = mock(AiMonthlyTrainingRunner.class);
        when(configMapper.selectList(any())).thenReturn(List.of(config(5L), config(7L)));
        when(monthlyRunner.run(org.mockito.ArgumentMatchers.eq(5L), any()))
                .thenThrow(new IllegalStateException("trainer unavailable"));
        when(monthlyRunner.run(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(
                new AiEvolutionAutomationService.CycleResult("SKIPPED", 0, 0, 0, "low sample"));
        when(jobLogMapper.insert(any(AiLearningJobLog.class))).thenAnswer(invocation -> {
            ((AiLearningJobLog) invocation.getArgument(0)).id = 101L;
            return 1;
        });
        AiEvolutionAutomationService service = new AiEvolutionAutomationServiceImpl(
                configMapper, jobLogMapper, weeklyRunner, monthlyRunner);

        service.runMonthlyForEnabledUsers();

        ArgumentCaptor<AiLearningJobLog> jobs = ArgumentCaptor.forClass(AiLearningJobLog.class);
        verify(jobLogMapper, times(2)).updateById(jobs.capture());
        assertThat(jobs.getAllValues()).extracting(job -> job.status)
                .containsExactly("FAILED", "SKIPPED");
        assertThat(jobs.getAllValues().get(0).errorMessage).contains("trainer unavailable");
    }

    private static AiModelConfig config(Long userId) {
        AiModelConfig value = new AiModelConfig();
        value.userId = userId;
        value.autoClosePipelineEnabled = 1;
        value.deleted = 0;
        return value;
    }
}
