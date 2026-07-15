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
        assertThat(fixture.config.autoClosePipelineLastStatus).isEqualTo("FAILED");
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
        return new Fixture(config, dailyResearchService, preparationService, pipelineRunMapper, service);
    }

    private record Fixture(
            AiModelConfig config,
            AiGlobalDailyResearchService dailyResearchService,
            AiGlobalResearchPreparationService preparationService,
            AiPipelineRunMapper pipelineRunMapper,
            AutoClosePipelineServiceImpl service
    ) {
    }
}
