package com.maogou.stock.controller;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.dto.settings.ModelConfigResponse;
import com.maogou.stock.dto.settings.SchedulerStatusResponse;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.AutoClosePipelineService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.TradingCalendarService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
        AiLearningJobLogMapper jobLogMapper = mock(AiLearningJobLogMapper.class);
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

        SettingsController controller = new SettingsController(
                modelConfigService,
                jobLogMapper,
                properties,
                tradingCalendarService,
                aiResearchDailyReportService,
                autoClosePipelineService
        );

        SchedulerStatusResponse response = controller.schedulerStatus().data();

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
    }

    @Test
    void manualClosePipelineEndpointRunsTheSameBackendPipeline() {
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        AiLearningJobLogMapper jobLogMapper = mock(AiLearningJobLogMapper.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        AiResearchDailyReportService reportService = mock(AiResearchDailyReportService.class);
        AutoClosePipelineService autoClosePipelineService = mock(AutoClosePipelineService.class);
        SettingsController controller = new SettingsController(
                modelConfigService,
                jobLogMapper,
                new AppProperties(),
                tradingCalendarService,
                reportService,
                autoClosePipelineService);

        String message = controller.runAutoClosePipelineNow().data();

        assertThat(message).isEqualTo("每日收盘投研流水线已执行");
        verify(autoClosePipelineService).runCurrentUserNow();
    }
}
