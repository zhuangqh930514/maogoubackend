package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiLearningJobLog;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoClosePipelineV2BridgeTest {

    @Test
    void enabledConfigRunsTheV2DailyPipeline() {
        AiModelConfigMapper configMapper = mock(AiModelConfigMapper.class);
        AiLearningJobLogMapper jobLogMapper = mock(AiLearningJobLogMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiGlobalDailyResearchService dailyPipelineServiceV2 = mock(AiGlobalDailyResearchService.class);
        AiGlobalResearchPreparationService preparationService = mock(AiGlobalResearchPreparationService.class);

        AiModelConfig config = new AiModelConfig();
        config.id = 7L;
        config.userId = 5L;
        config.autoClosePipelineEnabled = 1;
        when(tradingCalendarService.isTradingDay(LocalDate.now())).thenReturn(true);
        when(configMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(config));
        when(configMapper.selectById(7L)).thenReturn(config);
        when(jobLogMapper.insert(any(AiLearningJobLog.class))).thenAnswer(invocation -> {
            AiLearningJobLog job = invocation.getArgument(0);
            job.id = 88L;
            return 1;
        });
        when(preparationService.prepare(any(), any(), any(), any())).thenReturn(
                new AiGlobalResearchPreparationService.PreparedPipeline(11L, 91L, null, "real-input-fingerprint"));

        AiPipelineRun run = new AiPipelineRun();
        run.id = 91L;
        run.status = "SUCCESS";
        run.processedCount = 9;
        run.successCount = 9;
        run.failedCount = 0;
        when(dailyPipelineServiceV2.run(any())).thenReturn(new AiGlobalDailyResearchService.PipelineResult(run, List.of()));

        AutoClosePipelineServiceImpl service = new AutoClosePipelineServiceImpl(
                configMapper,
                jobLogMapper,
                tradingCalendarService,
                dailyPipelineServiceV2,
                preparationService);

        service.runEnabledPipelines();

        verify(dailyPipelineServiceV2).run(org.mockito.ArgumentMatchers.argThat(request ->
                request.dataBatchId().equals(11L)
                        && request.strategyReleaseId().equals(91L)
                        && request.inputFingerprint().equals("real-input-fingerprint")));
        assertThat(config.autoClosePipelineLastStatus).isEqualTo("SUCCESS");
    }

    @Test
    void manualRunPropagatesPipelineFailureInsteadOfReturningFalseSuccess() {
        AiModelConfigMapper configMapper = mock(AiModelConfigMapper.class);
        AiLearningJobLogMapper jobLogMapper = mock(AiLearningJobLogMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiGlobalDailyResearchService dailyPipelineServiceV2 = mock(AiGlobalDailyResearchService.class);
        AiGlobalResearchPreparationService preparationService = mock(AiGlobalResearchPreparationService.class);
        AiModelConfig config = new AiModelConfig();
        config.id = 7L;
        config.userId = 5L;
        when(configMapper.selectOne(any(QueryWrapper.class))).thenReturn(config);
        when(configMapper.selectById(7L)).thenReturn(config);
        when(jobLogMapper.insert(any(AiLearningJobLog.class))).thenAnswer(invocation -> {
            AiLearningJobLog job = invocation.getArgument(0);
            job.id = 89L;
            return 1;
        });
        when(preparationService.prepare(any(), any(), any(), any())).thenReturn(
                new AiGlobalResearchPreparationService.PreparedPipeline(11L, 91L, null, "real-input-fingerprint"));
        when(dailyPipelineServiceV2.run(any())).thenThrow(new IllegalStateException("pipeline storage unavailable"));
        AutoClosePipelineServiceImpl service = new AutoClosePipelineServiceImpl(
                configMapper, jobLogMapper, tradingCalendarService, dailyPipelineServiceV2, preparationService);

        assertThatThrownBy(() -> AuthContext.runAs(5L, service::runCurrentUserNow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pipeline storage unavailable");
        assertThat(config.autoClosePipelineLastStatus).isEqualTo("FAILED");
    }

    @Test
    void concurrentInstanceCannotOverwriteCompletedConfigStatusWithRunning() {
        AiModelConfigMapper configMapper = mock(AiModelConfigMapper.class);
        AiLearningJobLogMapper jobLogMapper = mock(AiLearningJobLogMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiGlobalDailyResearchService dailyPipelineServiceV2 = mock(AiGlobalDailyResearchService.class);
        AiGlobalResearchPreparationService preparationService = mock(AiGlobalResearchPreparationService.class);
        AiModelConfig config = new AiModelConfig();
        config.id = 7L;
        config.userId = 5L;
        config.autoClosePipelineLastStatus = "SUCCESS";
        config.autoClosePipelineLastMessage = "获胜实例已完成";
        when(configMapper.selectOne(any(QueryWrapper.class))).thenReturn(config);
        when(configMapper.selectById(7L)).thenReturn(config);
        when(preparationService.prepare(any(), any(), any(), any())).thenReturn(
                new AiGlobalResearchPreparationService.PreparedPipeline(11L, 91L, null, "real-input-fingerprint"));
        when(dailyPipelineServiceV2.run(any())).thenThrow(
                new IllegalStateException("每日投研流水线正在由其他实例执行，请稍后查看结果"));
        AutoClosePipelineServiceImpl service = new AutoClosePipelineServiceImpl(
                configMapper, jobLogMapper, tradingCalendarService, dailyPipelineServiceV2, preparationService);

        assertThatThrownBy(() -> AuthContext.runAs(5L, service::runCurrentUserNow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("其他实例");
        assertThat(config.autoClosePipelineLastStatus).isEqualTo("SUCCESS");
        assertThat(config.autoClosePipelineLastMessage).isEqualTo("获胜实例已完成");
    }
}
