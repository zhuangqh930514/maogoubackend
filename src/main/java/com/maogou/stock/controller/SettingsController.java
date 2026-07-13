package com.maogou.stock.controller;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.common.ApiResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiLearningJobLog;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.dto.settings.ConnectionTestResponse;
import com.maogou.stock.dto.settings.ModelConfigRequest;
import com.maogou.stock.dto.settings.ModelConfigResponse;
import com.maogou.stock.dto.settings.SchedulerJobLogResponse;
import com.maogou.stock.dto.settings.SchedulerStatusResponse;
import com.maogou.stock.dto.settings.SchedulerToggleRequest;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.AutoClosePipelineService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.TradingCalendarService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ModelConfigService modelConfigService;
    private final AiLearningJobLogMapper jobLogMapper;
    private final AppProperties properties;
    private final TradingCalendarService tradingCalendarService;
    private final AiResearchDailyReportService aiResearchDailyReportService;
    private final AutoClosePipelineService autoClosePipelineService;

    public SettingsController(
            ModelConfigService modelConfigService,
            AiLearningJobLogMapper jobLogMapper,
            AppProperties properties,
            TradingCalendarService tradingCalendarService,
            AiResearchDailyReportService aiResearchDailyReportService,
            AutoClosePipelineService autoClosePipelineService
    ) {
        this.modelConfigService = modelConfigService;
        this.jobLogMapper = jobLogMapper;
        this.properties = properties;
        this.tradingCalendarService = tradingCalendarService;
        this.aiResearchDailyReportService = aiResearchDailyReportService;
        this.autoClosePipelineService = autoClosePipelineService;
    }

    @GetMapping("/model")
    public ApiResponse<ModelConfigResponse> model() {
        return ApiResponse.ok(modelConfigService.current());
    }

    @PutMapping("/model")
    public ApiResponse<ModelConfigResponse> saveModel(@RequestBody @Valid ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.save(request));
    }

    @PostMapping("/model/test")
    public ApiResponse<ConnectionTestResponse> test(@RequestBody @Valid ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.testConnection(request));
    }

    @GetMapping("/scheduler/status")
    public ApiResponse<SchedulerStatusResponse> schedulerStatus() {
        AiModelConfig entity = modelConfigService.currentEntity();
        ModelConfigResponse config = modelConfigService.current();
        AppProperties.Scheduler scheduler = properties.getScheduler();
        AiResearchDailyReportService.ReportView latestDailyReport = aiResearchDailyReportService.latestOrNull(AuthContext.currentUserIdOrDefault());
        return ApiResponse.ok(new SchedulerStatusResponse(
                scheduler.isEnabled(),
                scheduler.getNewsFixedRateMs(),
                scheduler.getIntradayAnalysisFixedRateMs(),
                scheduler.getCloseAnalysisCron(),
                scheduler.getEvolutionReviewCron(),
                config.intradayInterval(),
                config.closeTime(),
                config.analysisScope(),
                nextCloseAnalysisTime(config.closeTime()),
                "交易日 16:10",
                entity.autoClosePipelineEnabled != null && entity.autoClosePipelineEnabled == 1,
                "RUNNING".equalsIgnoreCase(nullToEmpty(entity.autoClosePipelineLastStatus)),
                scheduler.getAutoClosePipelineCron(),
                nextAutoClosePipelineTime(),
                formatDateTime(entity.autoClosePipelineLastRunAt),
                formatDateTime(entity.autoClosePipelineLastFinishedAt),
                nullToEmpty(entity.autoClosePipelineLastStatus),
                nullToEmpty(entity.autoClosePipelineLastMessage),
                scheduler.getWeeklyEvolutionCron(),
                nextCronTime(scheduler.getWeeklyEvolutionCron()),
                scheduler.getMonthlyTrainingCron(),
                nextCronTime(scheduler.getMonthlyTrainingCron()),
                latestDailyReport == null ? null : new SchedulerStatusResponse.ResearchDailyReportSummary(
                        latestDailyReport.id(),
                        latestDailyReport.tradeDate() == null ? "" : latestDailyReport.tradeDate().toString(),
                        latestDailyReport.reportVersion(),
                        latestDailyReport.reportStatus(),
                        latestDailyReport.title(),
                        formatDateTime(latestDailyReport.generatedAt()),
                        latestDailyReport.recommendationCount(),
                        latestDailyReport.watchCount(),
                        latestDailyReport.avoidCount(),
                        latestDailyReport.freshnessStatus()
                )
        ));
    }

    @GetMapping("/scheduler/job-logs")
    public ApiResponse<List<SchedulerJobLogResponse>> schedulerJobLogs(Integer limit) {
        int size = Math.max(1, Math.min(limit == null ? 20 : limit, 50));
        List<AiLearningJobLog> rows = jobLogMapper.selectList(new QueryWrapper<AiLearningJobLog>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .orderByDesc("started_at")
                .last("LIMIT " + size));
        return ApiResponse.ok(rows.stream()
                .map(item -> new SchedulerJobLogResponse(
                        item.id,
                        item.jobName,
                        item.jobType,
                        item.status,
                        item.startedAt,
                        item.finishedAt,
                        item.processedCount,
                        item.successCount,
                        item.failedCount,
                        item.errorMessage
                ))
                .toList());
    }

    @PutMapping("/scheduler/auto-close-pipeline")
    public ApiResponse<SchedulerStatusResponse> toggleAutoClosePipeline(@RequestBody SchedulerToggleRequest request) {
        modelConfigService.setAutoClosePipelineEnabled(request.enabled());
        return schedulerStatus();
    }

    @PostMapping("/scheduler/auto-close-pipeline/run")
    public ApiResponse<String> runAutoClosePipelineNow() {
        autoClosePipelineService.runCurrentUserNow();
        return ApiResponse.ok("每日收盘投研流水线已执行");
    }

    private static String nextCloseAnalysisTime(String closeTime) {
        LocalTime time = LocalTime.parse(closeTime == null || closeTime.isBlank() ? "15:30" : closeTime, DateTimeFormatter.ofPattern("HH:mm"));
        LocalDate today = LocalDate.now();
        LocalDateTime next = LocalDateTime.of(today, time);
        if (!next.isAfter(LocalDateTime.now())) {
            next = next.plusDays(1);
        }
        return next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String nextAutoClosePipelineTime() {
        LocalDateTime next = tradingCalendarService.nextTradingDateTime(LocalDateTime.now(), 16, 0);
        return next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String nextCronTime(String expression) {
        if (expression == null || expression.isBlank() || !CronExpression.isValidExpression(expression)) {
            return "";
        }
        LocalDateTime next = CronExpression.parse(expression).next(LocalDateTime.now());
        return formatDateTime(next);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
