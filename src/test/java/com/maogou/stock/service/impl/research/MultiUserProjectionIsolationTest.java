package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.mapper.AiModelConfigMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.impl.AutoClosePipelineServiceImpl;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import com.maogou.stock.service.research.AiResearchOperationsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiUserProjectionIsolationTest {

    @Test
    void unauthenticatedCodeNeverFallsBackToUserOne() {
        assertThat(AuthContext.currentUserId()).isEmpty();
        assertThatThrownBy(AuthContext::currentUserIdOrDefault)
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("登录");
    }

    @Test
    void oneGlobalRunProjectsEveryEnabledUserAndOneFailureDoesNotPolluteTheNextUser() {
        AiModelConfigMapper configMapper = mock(AiModelConfigMapper.class);
        TradingCalendarService calendarService = mock(TradingCalendarService.class);
        AiGlobalDailyResearchService dailyResearchService = mock(AiGlobalDailyResearchService.class);
        AiGlobalResearchPreparationService preparationService = mock(AiGlobalResearchPreparationService.class);
        AiResearchOperationsService operationsService = mock(AiResearchOperationsService.class);
        AiPipelineRunMapper pipelineRunMapper = mock(AiPipelineRunMapper.class);
        LocalDate tradeDate = LocalDate.of(2026, 7, 15);

        AiModelConfig first = enabledConfig(51L, 5L);
        AiModelConfig second = enabledConfig(61L, 6L);
        when(calendarService.isTradingDay(any())).thenReturn(true);
        when(calendarService.latestExpectedKlineDate(any())).thenReturn(tradeDate);
        when(configMapper.selectEnabledAutomationConfigsAfter(anyLong(), eq(100)))
                .thenReturn(List.of(first, second), List.of());
        when(configMapper.selectById(51L)).thenReturn(first);
        when(configMapper.selectById(61L)).thenReturn(second);
        when(preparationService.prepare(eq(tradeDate), any(), eq("SCHEDULED:GLOBAL_DAILY:" + tradeDate)))
                .thenReturn(new AiGlobalResearchPreparationService.PreparedPipeline(
                        701L, 801L, "global-input"));

        AiPipelineRun globalRun = new AiPipelineRun();
        globalRun.id = 901L;
        globalRun.tradeDate = tradeDate;
        globalRun.status = "SUCCESS";
        globalRun.processedCount = 8;
        globalRun.successCount = 8;
        globalRun.failedCount = 0;
        when(dailyResearchService.run(any())).thenReturn(
                new AiGlobalDailyResearchService.PipelineResult(globalRun, List.of()));
        when(operationsService.runUserProjection(eq(5L), any()))
                .thenThrow(new IllegalStateException("用户 5 投影失败"));
        when(operationsService.runUserProjection(eq(6L), any()))
                .thenReturn(new ResearchLabPayloads.ActionAccepted(906L, "PENDING"));

        AutoClosePipelineServiceImpl service = new AutoClosePipelineServiceImpl(
                configMapper, calendarService, dailyResearchService, preparationService,
                operationsService, pipelineRunMapper);

        assertThat(AuthContext.currentUserId()).isEmpty();
        service.runEnabledPipelines();
        assertThat(AuthContext.currentUserId()).isEmpty();

        verify(dailyResearchService, times(1)).run(any());
        verify(operationsService).runUserProjection(eq(5L), any());
        ArgumentCaptor<ResearchLabPayloads.ActionRequest> secondRequest =
                ArgumentCaptor.forClass(ResearchLabPayloads.ActionRequest.class);
        verify(operationsService).runUserProjection(eq(6L), secondRequest.capture());
        assertThat(secondRequest.getValue().userId()).isNull();
        assertThat(secondRequest.getValue().parentPipelineRunId()).isEqualTo(901L);
        assertThat(secondRequest.getValue().tradeDate()).isEqualTo(tradeDate);
        assertThat(first.autoClosePipelineLastStatus).isEqualTo("FAILED");
        assertThat(second.autoClosePipelineLastStatus).isEqualTo("PENDING");
    }

    private static AiModelConfig enabledConfig(Long id, Long userId) {
        AiModelConfig config = new AiModelConfig();
        config.id = id;
        config.userId = userId;
        config.autoClosePipelineEnabled = 1;
        config.deleted = 0;
        return config;
    }
}
