package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import com.maogou.stock.service.research.AiResearchOperationsService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoClosePipelineServiceImplTest {

    @Test
    void manualRunPropagatesGlobalPipelineFailure() {
        Fixture fixture = fixture();
        when(fixture.dailyResearchService.run(any()))
                .thenThrow(new IllegalStateException("pipeline storage unavailable"));

        assertThatThrownBy(() -> AuthContext.runAs(5L, fixture.service::runCurrentUserNow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pipeline storage unavailable");
        assertThat(fixture.config.autoClosePipelineLastStatus).isNull();
        verify(fixture.configMapper, never()).updateById(org.mockito.ArgumentMatchers.<AiModelConfig>any());
    }

    @Test
    void concurrentInstanceDoesNotOverwriteTheWinningStatus() {
        Fixture fixture = fixture();
        fixture.config.autoClosePipelineLastStatus = "SUCCESS";
        fixture.config.autoClosePipelineLastMessage = "获胜实例已完成";
        when(fixture.dailyResearchService.run(any())).thenThrow(
                new IllegalStateException("每日投研流水线正在由其他实例执行，请稍后查看结果"));

        assertThatThrownBy(() -> AuthContext.runAs(5L, fixture.service::runCurrentUserNow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("其他实例");
        assertThat(fixture.config.autoClosePipelineLastStatus).isEqualTo("SUCCESS");
        assertThat(fixture.config.autoClosePipelineLastMessage).isEqualTo("获胜实例已完成");
    }

    @Test
    void waitingSourceRecoveryResumesPersistedRunWithoutPreparingAnotherOne() {
        Fixture fixture = fixture();
        AiPipelineRun waiting = new AiPipelineRun();
        waiting.id = 41L;
        waiting.tradeDate = LocalDate.of(2026, 7, 15);
        waiting.strategyReleaseId = 91L;
        waiting.modelVersionId = 92L;
        waiting.idempotencyKey = "SCHEDULED:GLOBAL_DAILY:2026-07-15";
        waiting.inputFingerprint = "persisted-input";
        waiting.startedAt = LocalDateTime.of(2026, 7, 15, 16, 0);

        AiPipelineRun completed = new AiPipelineRun();
        completed.id = waiting.id;
        completed.tradeDate = waiting.tradeDate;
        completed.status = "SUCCESS";
        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt()))
                .thenReturn(List.of(waiting));
        when(fixture.calendarService.isTradingDay(LocalDate.of(2026, 7, 15))).thenReturn(true);
        when(fixture.dailyResearchService.run(any())).thenReturn(
                new AiGlobalDailyResearchService.PipelineResult(completed, List.of()));

        fixture.service.retryWaitingPipelines();

        verify(fixture.dailyResearchService).run(new AiGlobalDailyResearchService.PipelineRequest(
                waiting.tradeDate,
                waiting.strategyReleaseId,
                waiting.modelVersionId,
                waiting.idempotencyKey,
                waiting.inputFingerprint,
                waiting.startedAt));
        verify(fixture.preparationService, never()).prepare(any(), any(), any());
    }

    @Test
    void completedGlobalRunUsesItsOwnUserProjectionIdempotencyKey() {
        Fixture fixture = fixture();
        AiPipelineRun waiting = new AiPipelineRun();
        waiting.id = 41L;
        waiting.tradeDate = LocalDate.of(2026, 7, 15);
        waiting.strategyReleaseId = 91L;
        waiting.modelVersionId = 92L;
        waiting.idempotencyKey = "SCHEDULED:GLOBAL_DAILY:2026-07-15";
        waiting.inputFingerprint = "persisted-input";
        waiting.startedAt = LocalDateTime.of(2026, 7, 15, 16, 0);
        AiPipelineRun completed = new AiPipelineRun();
        completed.id = 42L;
        completed.tradeDate = waiting.tradeDate;
        completed.strategyReleaseId = waiting.strategyReleaseId;
        completed.modelVersionId = waiting.modelVersionId;
        completed.status = "PARTIAL_SUCCESS";

        fixture.config.autoClosePipelineEnabled = 1;
        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt())).thenReturn(List.of(waiting));
        when(fixture.calendarService.isTradingDay(waiting.tradeDate)).thenReturn(true);
        when(fixture.dailyResearchService.run(any())).thenReturn(
                new AiGlobalDailyResearchService.PipelineResult(completed, List.of()));
        when(fixture.configMapper.selectEnabledAutomationConfigsAfter(eq(0L), anyInt()))
                .thenReturn(List.of(fixture.config));

        fixture.service.retryWaitingPipelines();

        verify(fixture.operationsService).runUserProjection(eq(5L),
                org.mockito.ArgumentMatchers.argThat(request -> request.parentPipelineRunId().equals(42L)
                        && request.idempotencyKey().equals("SCHEDULED:USER_DAILY:5:2026-07-15:42")));
    }

    @Test
    void retryWaitingPipelinesAbandonsNonTradingDayRuns() {
        Fixture fixture = fixture();
        AiPipelineRun waiting = new AiPipelineRun();
        waiting.id = 64L;
        waiting.tradeDate = LocalDate.of(2026, 7, 19);
        waiting.status = "WAITING_SOURCE";

        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt()))
                .thenReturn(List.of(waiting));
        when(fixture.calendarService.isTradingDay(LocalDate.of(2026, 7, 19))).thenReturn(false);
        when(fixture.calendarService.latestExpectedKlineDate(any())).thenReturn(LocalDate.of(2026, 7, 17));

        fixture.service.retryWaitingPipelines();

        verify(fixture.dailyResearchService, never()).run(any());
        verify(fixture.pipelineRunMapper).updateById(waiting);
        assertThat(waiting.status).isEqualTo("FAILED");
        assertThat(waiting.currentStep).isEqualTo("INVALID_TRADE_DATE");
        assertThat(waiting.errorMessage).contains("2026-07-17");
    }

    @Test
    void recoveryMarksStaleUserProjectionAndResubmitsItAgainstTheOriginalGlobalRun() {
        Fixture fixture = fixture();
        AiPipelineRun stale = new AiPipelineRun();
        stale.id = 193L;
        stale.scopeType = "USER";
        stale.ownerUserId = 5L;
        stale.parentRunId = 191L;
        stale.pipelineType = "USER_DAILY_PROJECTION";
        stale.tradeDate = LocalDate.of(2026, 7, 17);
        stale.idempotencyKey = "SCHEDULED:USER_DAILY:5:2026-07-17";
        stale.currentStep = "PROJECT_USER_DAILY";
        stale.updatedAt = LocalDateTime.now().minusHours(2);

        AiPipelineRun parent = new AiPipelineRun();
        parent.id = 191L;
        parent.status = "SUCCESS";
        parent.tradeDate = stale.tradeDate;
        parent.strategyReleaseId = 91L;
        parent.modelVersionId = 92L;

        when(fixture.pipelineRunMapper.selectStaleRunning(any(), any(), anyInt())).thenReturn(List.of(stale));
        when(fixture.pipelineRunMapper.recoverStaleRunning(any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.pipelineRunMapper.selectById(191L)).thenReturn(parent);
        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt())).thenReturn(List.of());

        fixture.service.retryWaitingPipelines();

        verify(fixture.pipelineRunMapper).recoverStaleRunning(
                org.mockito.ArgumentMatchers.eq(193L), any(), any(),
                org.mockito.ArgumentMatchers.contains("自动回收"),
                org.mockito.ArgumentMatchers.contains("PROJECT_USER_DAILY"));
        verify(fixture.operationsService).runUserProjection(
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.argThat(request -> request.parentPipelineRunId().equals(191L)
                        && request.idempotencyKey().equals(stale.idempotencyKey)));
    }

    @Test
    void recoveryResubmitsStaleGlobalDailyResearchUsingItsOriginalInput() {
        Fixture fixture = fixture();
        AiPipelineRun stale = new AiPipelineRun();
        stale.id = 74L;
        stale.scopeType = "GLOBAL";
        stale.pipelineType = "GLOBAL_DAILY_RESEARCH";
        stale.tradeDate = LocalDate.of(2026, 7, 17);
        stale.strategyReleaseId = 91L;
        stale.modelVersionId = 92L;
        stale.idempotencyKey = "SCHEDULED:GLOBAL_DAILY:2026-07-17";
        stale.inputFingerprint = "persisted-input";
        stale.startedAt = LocalDateTime.of(2026, 7, 17, 16, 0);
        stale.currentStep = "COMPUTE_FACTORS";
        stale.updatedAt = LocalDateTime.now().minusHours(2);

        when(fixture.pipelineRunMapper.selectStaleRunning(any(), any(), anyInt())).thenReturn(List.of(stale));
        when(fixture.pipelineRunMapper.recoverStaleRunning(any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt())).thenReturn(List.of());

        fixture.service.retryWaitingPipelines();

        verify(fixture.dailyResearchService).run(new AiGlobalDailyResearchService.PipelineRequest(
                stale.tradeDate, stale.strategyReleaseId, stale.modelVersionId,
                stale.idempotencyKey, stale.inputFingerprint, stale.startedAt));
    }

    @Test
    void recoveryTerminalizesInterruptedHistoricalBootstrapInsteadOfLeavingItRecoverableForever() {
        Fixture fixture = fixture();
        AiPipelineRun stale = new AiPipelineRun();
        stale.id = 74L;
        stale.scopeType = "GLOBAL";
        stale.pipelineType = "GLOBAL_HISTORICAL_BOOTSTRAP";
        stale.tradeDate = LocalDate.of(2026, 7, 17);
        stale.currentStep = "MATURE_HISTORICAL_SAMPLE_LABELS";
        stale.errorMessage = "历史训练运行中断";
        stale.updatedAt = LocalDateTime.now().minusHours(2);

        when(fixture.pipelineRunMapper.selectStaleRunning(any(), any(), anyInt())).thenReturn(List.of(stale));
        when(fixture.pipelineRunMapper.recoverStaleRunning(any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt())).thenReturn(List.of());

        fixture.service.retryWaitingPipelines();

        verify(fixture.pipelineRunMapper).finalizeRecoverableRunRequiringManualRestart(
                eq(74L), any(), org.mockito.ArgumentMatchers.contains("无法安全自动重放"),
                org.mockito.ArgumentMatchers.contains("MATURE_HISTORICAL_SAMPLE_LABELS"));
        verify(fixture.dailyResearchService, never()).run(any());
        verify(fixture.operationsService, never()).runUserProjection(any(), any());
    }

    @Test
    void recoveryTerminalizesPreviouslyRecoveredNonReplayableRuns() {
        Fixture fixture = fixture();
        AiPipelineRun recovered = new AiPipelineRun();
        recovered.id = 75L;
        recovered.scopeType = "GLOBAL";
        recovered.pipelineType = "GLOBAL_MONTHLY_TRAINING";
        recovered.currentStep = "RUN_TRAINING";
        recovered.errorMessage = "模型训练超时";

        when(fixture.pipelineRunMapper.selectStaleRunning(any(), any(), anyInt())).thenReturn(List.of());
        when(fixture.pipelineRunMapper.selectRecoverableRunsRequiringManualRestart(any(), anyInt()))
                .thenReturn(List.of(recovered));
        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt())).thenReturn(List.of());

        fixture.service.retryWaitingPipelines();

        verify(fixture.pipelineRunMapper).finalizeRecoverableRunRequiringManualRestart(
                eq(75L), any(), org.mockito.ArgumentMatchers.contains("无法安全自动重放"),
                org.mockito.ArgumentMatchers.contains("模型训练超时"));
    }

    @Test
    void dueUserReportRetryIsSubmittedAgainstTheOriginalCompletedGlobalRun() {
        Fixture fixture = fixture();
        AiPipelineRun retry = new AiPipelineRun();
        retry.id = 299L;
        retry.scopeType = "USER";
        retry.ownerUserId = 5L;
        retry.parentRunId = 191L;
        retry.pipelineType = "USER_DAILY_PROJECTION";
        retry.idempotencyKey = "SCHEDULED:USER_DAILY:5:2026-07-17";
        retry.status = "PARTIAL_SUCCESS";

        AiPipelineRun parent = new AiPipelineRun();
        parent.id = 191L;
        parent.status = "SUCCESS";
        parent.tradeDate = LocalDate.of(2026, 7, 17);
        parent.strategyReleaseId = 91L;
        parent.modelVersionId = 92L;

        fixture.config.autoClosePipelineEnabled = 1;
        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt())).thenReturn(List.of());
        when(fixture.pipelineRunMapper.selectDueUserProjectionRetries(any(), anyInt())).thenReturn(List.of(retry));
        when(fixture.configMapper.selectEnabledAutomationConfigsAfter(eq(0L), anyInt()))
                .thenReturn(List.of(fixture.config));
        when(fixture.pipelineRunMapper.selectById(191L)).thenReturn(parent);

        fixture.service.retryWaitingPipelines();

        verify(fixture.operationsService).runUserProjection(eq(5L),
                org.mockito.ArgumentMatchers.argThat(request -> request.parentPipelineRunId().equals(191L)
                        && request.tradeDate().equals(parent.tradeDate)
                        && request.idempotencyKey().equals(retry.idempotencyKey)));
    }

    @Test
    void dueUserReportRetryIsPausedWhenTheUserDisabledDailyAutomation() {
        Fixture fixture = fixture();
        AiPipelineRun retry = new AiPipelineRun();
        retry.id = 300L;
        retry.scopeType = "USER";
        retry.ownerUserId = 5L;
        retry.pipelineType = "USER_DAILY_PROJECTION";
        retry.status = "PARTIAL_SUCCESS";

        when(fixture.pipelineRunMapper.selectDueGlobalDailyRuns(any(), anyInt())).thenReturn(List.of());
        when(fixture.pipelineRunMapper.selectDueUserProjectionRetries(any(), anyInt())).thenReturn(List.of(retry));
        when(fixture.configMapper.selectEnabledAutomationConfigsAfter(eq(0L), anyInt())).thenReturn(List.of());

        fixture.service.retryWaitingPipelines();

        verify(fixture.pipelineRunMapper).pauseUserProjectionRetry(
                eq(300L), any(), org.mockito.ArgumentMatchers.contains("已暂停"),
                org.mockito.ArgumentMatchers.contains("用户=5"));
        verify(fixture.operationsService, never()).runUserProjection(any(), any());
    }

    private static Fixture fixture() {
        AiModelConfigMapper configMapper = mock(AiModelConfigMapper.class);
        TradingCalendarService calendarService = mock(TradingCalendarService.class);
        AiGlobalDailyResearchService dailyResearchService = mock(AiGlobalDailyResearchService.class);
        AiGlobalResearchPreparationService preparationService = mock(AiGlobalResearchPreparationService.class);
        AiResearchOperationsService operationsService = mock(AiResearchOperationsService.class);
        AiPipelineRunMapper pipelineRunMapper = mock(AiPipelineRunMapper.class);
        AiModelConfig config = new AiModelConfig();
        config.id = 7L;
        config.userId = 5L;
        when(configMapper.selectOne(any(QueryWrapper.class))).thenReturn(config);
        when(calendarService.latestExpectedKlineDate(any())).thenReturn(LocalDate.of(2026, 7, 15));
        when(preparationService.prepare(any(), any(), any())).thenReturn(
                new AiGlobalResearchPreparationService.PreparedPipeline(
                        91L, null, "real-input-fingerprint"));
        AutoClosePipelineServiceImpl service = new AutoClosePipelineServiceImpl(
                configMapper, calendarService, dailyResearchService, preparationService,
                operationsService, pipelineRunMapper);
        return new Fixture(configMapper, config, calendarService, dailyResearchService, preparationService,
                operationsService, pipelineRunMapper, service);
    }

    private record Fixture(
            AiModelConfigMapper configMapper,
            AiModelConfig config,
            TradingCalendarService calendarService,
            AiGlobalDailyResearchService dailyResearchService,
            AiGlobalResearchPreparationService preparationService,
            AiResearchOperationsService operationsService,
            AiPipelineRunMapper pipelineRunMapper,
            AutoClosePipelineServiceImpl service
    ) {
    }
}
