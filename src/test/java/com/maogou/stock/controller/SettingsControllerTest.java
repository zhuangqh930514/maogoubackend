package com.maogou.stock.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.dto.settings.ModelConfigResponse;
import com.maogou.stock.dto.settings.SchedulerJobLogResponse;
import com.maogou.stock.dto.settings.SchedulerStatusResponse;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.AutoClosePipelineService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.TradingCalendarService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class SettingsControllerTest {

    @Test
    void schedulerStatusIncludesLatestResearchDailyReportSummary() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        AiPipelineRunMapper pipelineRunMapper = mock(AiPipelineRunMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiResearchDailyReportService aiResearchDailyReportService = mock(AiResearchDailyReportService.class);
        AutoClosePipelineService autoClosePipelineService = mock(AutoClosePipelineService.class);

        AppProperties properties = new AppProperties();
        AppProperties.Scheduler scheduler = new AppProperties.Scheduler();
        scheduler.setEnabled(true);
        scheduler.setNewsFixedRateMs(300000);
        scheduler.setIntradayAnalysisFixedRateMs(1800000);
        scheduler.setCloseAnalysisCron("0 30 15 * * MON-FRI");
        scheduler.setEvolutionReviewCron("0 10 16 * * MON-FRI");
        scheduler.setAutoClosePipelineCron("0 0 16 * * MON-FRI");
        scheduler.setWeeklyEvolutionCron("0 0 18 * * FRI");
        scheduler.setMonthlyTrainingCron("0 0 19 1 * *");
        properties.getScheduler().setEnabled(scheduler.isEnabled());
        properties.getScheduler().setNewsFixedRateMs(scheduler.getNewsFixedRateMs());
        properties.getScheduler().setIntradayAnalysisFixedRateMs(scheduler.getIntradayAnalysisFixedRateMs());
        properties.getScheduler().setCloseAnalysisCron(scheduler.getCloseAnalysisCron());
        properties.getScheduler().setEvolutionReviewCron(scheduler.getEvolutionReviewCron());
        properties.getScheduler().setAutoClosePipelineCron(scheduler.getAutoClosePipelineCron());
        properties.getScheduler().setWeeklyEvolutionCron(scheduler.getWeeklyEvolutionCron());
        properties.getScheduler().setMonthlyTrainingCron(scheduler.getMonthlyTrainingCron());

        AiModelConfig entity = new AiModelConfig();
        entity.autoClosePipelineEnabled = 1;
        entity.autoClosePipelineLastStatus = "SUCCESS";
        entity.autoClosePipelineLastMessage = "日报已生成";
        entity.autoClosePipelineLastRunAt = LocalDateTime.of(2026, 7, 13, 16, 0);
        entity.autoClosePipelineLastFinishedAt = LocalDateTime.of(2026, 7, 13, 16, 12);
        when(modelConfigService.currentEntity()).thenReturn(entity);
        when(modelConfigService.current()).thenReturn(new ModelConfigResponse(
                "http://localhost:11434/v1",
                "qwen3.6",
                "***",
                60000,
                BigDecimal.valueOf(0.2),
                2048,
                30,
                "15:30",
                "全部自选股",
                "prompt"
        ));
        when(tradingCalendarService.nextTradingDateTime(any(), eq(16), eq(0)))
                .thenReturn(LocalDateTime.of(2026, 7, 14, 16, 0));
        when(aiResearchDailyReportService.latestOrNull(anyLong()))
                .thenReturn(new AiResearchDailyReportService.ReportView(
                        9001L,
                        9101L,
                        LocalDate.of(2026, 7, 13),
                        3,
                        8001L,
                        7001L,
                        6001L,
                        9000L,
                        true,
                        "READY",
                        "2026-07-13 猫狗智投收盘日报",
                        "今日收盘后推荐 2 只，回避 1 只。",
                        "BALANCED",
                        2,
                        3,
                        1,
                        1,
                        "REALTIME",
                        BigDecimal.valueOf(91.2),
                        null,
                        "markdown",
                        LocalDateTime.of(2026, 7, 13, 16, 11)
                ));
        AiPipelineRun latestPipeline = new AiPipelineRun();
        latestPipeline.id = 8999L;
        latestPipeline.scopeType = "USER";
        latestPipeline.ownerUserId = 5L;
        latestPipeline.pipelineType = "USER_DAILY_PROJECTION";
        latestPipeline.status = "SUCCESS";
        latestPipeline.startedAt = LocalDateTime.of(2026, 7, 13, 16, 0);
        latestPipeline.finishedAt = LocalDateTime.of(2026, 7, 13, 16, 12);
        when(pipelineRunMapper.selectOne(any(QueryWrapper.class))).thenReturn(latestPipeline);
        AiPipelineRun globalRun = new AiPipelineRun();
        globalRun.id = 8998L;
        globalRun.scopeType = "GLOBAL";
        globalRun.pipelineType = "GLOBAL_DAILY_RESEARCH";
        globalRun.tradeDate = LocalDate.of(2026, 7, 13);
        globalRun.status = "PARTIAL_SUCCESS";
        globalRun.currentStep = "GENERATE_STOCK_REPORTS";
        globalRun.processedCount = 10;
        globalRun.successCount = 8;
        globalRun.failedCount = 2;
        globalRun.errorMessage = "2 只股票行情源超时";
        globalRun.nextRetryAt = LocalDateTime.of(2026, 7, 14, 9, 0);
        globalRun.startedAt = LocalDateTime.of(2026, 7, 13, 16, 0);
        globalRun.finishedAt = LocalDateTime.of(2026, 7, 13, 16, 12);
        AiPipelineRun userRun = new AiPipelineRun();
        userRun.id = 8997L;
        userRun.scopeType = "USER";
        userRun.ownerUserId = 5L;
        userRun.pipelineType = "USER_DAILY_PROJECTION";
        userRun.tradeDate = LocalDate.of(2026, 7, 13);
        userRun.status = "SUCCESS";
        userRun.processedCount = 3;
        userRun.successCount = 3;
        userRun.failedCount = 0;
        userRun.startedAt = LocalDateTime.of(2026, 7, 13, 16, 12);
        userRun.finishedAt = LocalDateTime.of(2026, 7, 13, 16, 13);
        when(pipelineRunMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(globalRun), List.of(userRun));

        SettingsController controller = new SettingsController(
                modelConfigService,
                pipelineRunMapper,
                properties,
                tradingCalendarService,
                aiResearchDailyReportService,
                autoClosePipelineService
        );

        SchedulerStatusResponse response = AuthContext.callAs(
                5L, () -> controller.schedulerStatus().data());

        assertThat(response.autoClosePipelineEnabled()).isTrue();
        assertThat(response.autoClosePipelineLastStatus()).isEqualTo("SUCCESS");
        assertThat(response.latestResearchDailyReport()).isNotNull();
        assertThat(response.latestResearchDailyReport().id()).isEqualTo(9001L);
        assertThat(response.latestResearchDailyReport().reportStatus()).isEqualTo("READY");
        assertThat(response.latestResearchDailyReport().recommendationCount()).isEqualTo(2);
        assertThat(response.latestResearchDailyReport().avoidCount()).isEqualTo(1);
        assertThat(response.latestResearchDailyReport().freshnessStatus()).isEqualTo("REALTIME");
        assertThat(response.weeklyEvolutionCron()).isEqualTo("0 0 18 * * FRI");
        assertThat(response.nextWeeklyEvolutionTime()).matches("\\d{4}-\\d{2}-\\d{2} 18:00:00");
        assertThat(response.monthlyTrainingCron()).isEqualTo("0 0 19 1 * *");
        assertThat(response.nextMonthlyTrainingTime()).matches("\\d{4}-\\d{2}-01 19:00:00");
        assertThat(response.globalResearch()).satisfies(summary -> {
            assertThat(summary.status()).isEqualTo("PARTIAL_SUCCESS");
            assertThat(summary.currentStep()).isEqualTo("GENERATE_STOCK_REPORTS");
            assertThat(summary.progressPercent()).isEqualTo(100);
            assertThat(summary.failedCount()).isEqualTo(2);
            assertThat(summary.primaryFailureReason()).isEqualTo("2 只股票行情源超时");
            assertThat(summary.nextRetryAt()).isEqualTo("2026-07-14 09:00:00");
        });
        assertThat(response.userDailyReport()).satisfies(summary -> {
            assertThat(summary.status()).isEqualTo("SUCCESS");
            assertThat(summary.progressPercent()).isEqualTo(100);
            assertThat(summary.durationMillis()).isEqualTo(60_000L);
        });
        assertThat(response.recentTradingDayTrend()).singleElement().satisfies(trend -> {
            assertThat(trend.tradeDate()).isEqualTo("2026-07-13");
            assertThat(trend.globalStatus()).isEqualTo("PARTIAL_SUCCESS");
            assertThat(trend.userDailyReportStatus()).isEqualTo("SUCCESS");
            assertThat(trend.globalFailedCount()).isEqualTo(2);
            assertThat(trend.userDailyReportFailedCount()).isEqualTo(0);
        });
    }

    @Test
    void schedulerStatusPrefersNewerUnifiedUserPipelineOverStaleLegacyStatus() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        AiPipelineRunMapper pipelineRunMapper = mock(AiPipelineRunMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiResearchDailyReportService reportService = mock(AiResearchDailyReportService.class);
        AutoClosePipelineService pipelineService = mock(AutoClosePipelineService.class);

        AiModelConfig entity = new AiModelConfig();
        entity.autoClosePipelineEnabled = 1;
        entity.autoClosePipelineLastStatus = "FAILED";
        entity.autoClosePipelineLastMessage = "预测批次不能为空";
        entity.autoClosePipelineLastRunAt = LocalDateTime.of(2026, 7, 13, 16, 0);
        entity.autoClosePipelineLastFinishedAt = LocalDateTime.of(2026, 7, 15, 16, 0);
        when(modelConfigService.currentEntity()).thenReturn(entity);
        when(modelConfigService.current()).thenReturn(new ModelConfigResponse(
                "http://localhost:11434/v1", "qwen3.6", "***", 60000,
                BigDecimal.valueOf(0.2), 2048, 30, "15:30", "全部自选股", "prompt"));
        when(tradingCalendarService.nextTradingDateTime(any(), eq(16), eq(0)))
                .thenReturn(LocalDateTime.of(2026, 7, 16, 16, 0));

        AiPipelineRun latest = new AiPipelineRun();
        latest.id = 3L;
        latest.scopeType = "USER";
        latest.ownerUserId = 5L;
        latest.pipelineType = "USER_DAILY_PROJECTION";
        latest.status = "SUCCESS";
        latest.processedCount = 32;
        latest.successCount = 32;
        latest.failedCount = 0;
        latest.startedAt = LocalDateTime.of(2026, 7, 15, 17, 12);
        latest.finishedAt = LocalDateTime.of(2026, 7, 15, 17, 13);
        latest.updatedAt = latest.finishedAt;
        when(pipelineRunMapper.selectOne(any(QueryWrapper.class))).thenReturn(latest);

        SettingsController controller = new SettingsController(
                modelConfigService, pipelineRunMapper, new AppProperties(),
                tradingCalendarService, reportService, pipelineService);

        SchedulerStatusResponse response = AuthContext.callAs(
                5L, () -> controller.schedulerStatus().data());

        assertThat(response.autoClosePipelineLastStatus()).isEqualTo("SUCCESS");
        assertThat(response.autoClosePipelineLastMessage()).isEqualTo("用户投研日报投影流水线 #3 已完成");
        assertThat(response.autoClosePipelineLastRunAt()).isEqualTo("2026-07-15 17:12:00");
        assertThat(response.autoClosePipelineLastFinishedAt()).isEqualTo("2026-07-15 17:13:00");
    }

    @Test
    void schedulerStatusFallsBackToTheActualGlobalRunWhenUserProjectionDoesNotExist() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        AiPipelineRunMapper pipelineRunMapper = mock(AiPipelineRunMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiResearchDailyReportService reportService = mock(AiResearchDailyReportService.class);
        AutoClosePipelineService pipelineService = mock(AutoClosePipelineService.class);
        AiModelConfig entity = new AiModelConfig();
        entity.autoClosePipelineEnabled = 1;
        entity.autoClosePipelineLastStatus = "SUCCESS";
        entity.autoClosePipelineLastMessage = "历史遗留状态";
        when(modelConfigService.currentEntity()).thenReturn(entity);
        when(modelConfigService.current()).thenReturn(new ModelConfigResponse(
                "http://localhost:11434/v1", "qwen3.6", "***", 60000,
                BigDecimal.valueOf(0.2), 2048, 30, "15:30", "全部自选股", "prompt"));
        when(tradingCalendarService.nextTradingDateTime(any(), eq(16), eq(0)))
                .thenReturn(LocalDateTime.of(2026, 7, 16, 16, 0));
        AiPipelineRun global = new AiPipelineRun();
        global.id = 88L;
        global.scopeType = "GLOBAL";
        global.pipelineType = "GLOBAL_DAILY_RESEARCH";
        global.status = "WAITING_SOURCE";
        global.startedAt = LocalDateTime.of(2026, 7, 15, 16, 0);
        when(pipelineRunMapper.selectOne(any(QueryWrapper.class))).thenReturn(null, global);

        SettingsController controller = new SettingsController(
                modelConfigService, pipelineRunMapper, new AppProperties(),
                tradingCalendarService, reportService, pipelineService);

        SchedulerStatusResponse response = AuthContext.callAs(
                5L, () -> controller.schedulerStatus().data());

        assertThat(response.autoClosePipelineLastStatus()).isEqualTo("WAITING_SOURCE");
        assertThat(response.autoClosePipelineLastMessage()).contains("全局日度研究流水线 #88")
                .contains("等待完整收盘数据");
    }

    @Test
    void manualClosePipelineEndpointRunsTheSameBackendPipeline() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        AiPipelineRunMapper pipelineRunMapper = mock(AiPipelineRunMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiResearchDailyReportService reportService = mock(AiResearchDailyReportService.class);
        AutoClosePipelineService autoClosePipelineService = mock(AutoClosePipelineService.class);
        SettingsController controller = new SettingsController(
                modelConfigService,
                pipelineRunMapper,
                new AppProperties(),
                tradingCalendarService,
                reportService,
                autoClosePipelineService);

        String message = controller.runAutoClosePipelineNow().data();

        assertThat(message).isEqualTo("每日收盘投研流水线已执行");
        verify(autoClosePipelineService).runCurrentUserNow();
    }

    @Test
    void schedulerLogsReadUnifiedGlobalAndOwnedUserPipelineRuns() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        AiPipelineRunMapper pipelineRunMapper = mock(AiPipelineRunMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiResearchDailyReportService reportService = mock(AiResearchDailyReportService.class);
        AutoClosePipelineService pipelineService = mock(AutoClosePipelineService.class);
        AiPipelineRun run = new AiPipelineRun();
        run.id = 91L;
        run.scopeType = "USER";
        run.ownerUserId = 5L;
        run.pipelineType = "USER_DAILY_PROJECTION";
        run.status = "SUCCESS";
        run.processedCount = 3;
        run.successCount = 3;
        run.failedCount = 0;
        run.startedAt = LocalDateTime.of(2026, 7, 15, 16, 6);
        run.finishedAt = LocalDateTime.of(2026, 7, 15, 16, 7);
        when(pipelineRunMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(run));
        SettingsController controller = new SettingsController(
                modelConfigService, pipelineRunMapper, new AppProperties(),
                tradingCalendarService, reportService, pipelineService);

        List<SchedulerJobLogResponse> response = AuthContext.callAs(
                5L, () -> controller.schedulerJobLogs(20).data());

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(91L);
            assertThat(item.jobName()).isEqualTo("用户投研日报投影");
            assertThat(item.jobType()).isEqualTo("USER_DAILY_PROJECTION");
            assertThat(item.status()).isEqualTo("SUCCESS");
            assertThat(item.currentStep()).isNull();
            assertThat(item.retryCount()).isNull();
            assertThat(item.nextRetryAt()).isNull();
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiPipelineRun>> queryCaptor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(pipelineRunMapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getCustomSqlSegment())
                .contains("scope_type", "owner_user_id", "pipeline_type");
        assertThat(queryCaptor.getValue().getParamNameValuePairs().values())
                .contains("GLOBAL", "USER", 5L, "GLOBAL_DAILY_RESEARCH", "USER_DAILY_PROJECTION");
    }
}
