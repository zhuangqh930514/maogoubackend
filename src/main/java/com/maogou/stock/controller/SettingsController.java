package com.maogou.stock.controller;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.common.ApiResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.dto.settings.ConnectionTestResponse;
import com.maogou.stock.dto.settings.ModelConfigRequest;
import com.maogou.stock.dto.settings.ModelConfigResponse;
import com.maogou.stock.dto.settings.SchedulerJobLogResponse;
import com.maogou.stock.dto.settings.SchedulerStatusResponse;
import com.maogou.stock.dto.settings.SchedulerToggleRequest;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ModelConfigService modelConfigService;
    private final AiPipelineRunMapper pipelineRunMapper;
    private final AppProperties properties;
    private final TradingCalendarService tradingCalendarService;
    private final AiResearchDailyReportService aiResearchDailyReportService;
    private final AutoClosePipelineService autoClosePipelineService;

    public SettingsController(
            ModelConfigService modelConfigService,
            AiPipelineRunMapper pipelineRunMapper,
            AppProperties properties,
            TradingCalendarService tradingCalendarService,
            AiResearchDailyReportService aiResearchDailyReportService,
            AutoClosePipelineService autoClosePipelineService
    ) {
        this.modelConfigService = modelConfigService;
        this.pipelineRunMapper = pipelineRunMapper;
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
        Long userId = AuthContext.currentUserIdOrDefault();
        AiResearchDailyReportService.ReportView latestDailyReport = aiResearchDailyReportService.latestOrNull(userId);
        PipelineStatusView pipelineStatus = latestPipelineStatus(userId, entity.autoClosePipelineEnabled != null
                && entity.autoClosePipelineEnabled == 1);
        List<AiPipelineRun> recentGlobalRuns = recentRuns("GLOBAL", null, "GLOBAL_DAILY_RESEARCH", 14);
        List<AiPipelineRun> recentUserRuns = recentRuns("USER", userId, "USER_DAILY_PROJECTION", 14);
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
                "RUNNING".equalsIgnoreCase(pipelineStatus.status()),
                scheduler.getAutoClosePipelineCron(),
                nextAutoClosePipelineTime(),
                formatDateTime(pipelineStatus.startedAt()),
                formatDateTime(pipelineStatus.finishedAt()),
                pipelineStatus.status(),
                pipelineStatus.message(),
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
                ),
                pipelineSummary(firstRun(recentGlobalRuns), "全局日度研究流水线"),
                pipelineSummary(firstRun(recentUserRuns), "用户投研日报投影流水线"),
                buildRecentTrend(recentGlobalRuns, recentUserRuns)
        ));
    }

    private List<AiPipelineRun> recentRuns(String scopeType, Long ownerUserId, String pipelineType, int limit) {
        QueryWrapper<AiPipelineRun> query = new QueryWrapper<AiPipelineRun>()
                .eq("scope_type", scopeType)
                .eq("pipeline_type", pipelineType)
                .orderByDesc("trade_date")
                .orderByDesc("id")
                .last("LIMIT " + Math.max(1, Math.min(limit, 30)));
        if ("USER".equals(scopeType)) {
            query.eq("owner_user_id", ownerUserId);
        }
        List<AiPipelineRun> runs = pipelineRunMapper.selectList(query);
        return runs == null ? List.of() : runs;
    }

    private static AiPipelineRun firstRun(List<AiPipelineRun> runs) {
        return runs == null || runs.isEmpty() ? null : runs.get(0);
    }

    private SchedulerStatusResponse.PipelineStatusSummary pipelineSummary(AiPipelineRun run, String pipelineName) {
        if (run == null) {
            return null;
        }
        int processed = nonNegative(run.processedCount);
        int success = nonNegative(run.successCount);
        int failed = nonNegative(run.failedCount);
        int completed = Math.min(processed, Math.max(0, success + failed));
        int progress = processed == 0
                ? "SUCCESS".equalsIgnoreCase(run.status) ? 100 : 0
                : Math.min(100, (int) Math.round(completed * 100d / processed));
        LocalDateTime started = run.startedAt == null ? run.createdAt : run.startedAt;
        LocalDateTime ended = run.finishedAt == null ? LocalDateTime.now() : run.finishedAt;
        long duration = started == null ? 0 : Math.max(0, java.time.Duration.between(started, ended).toMillis());
        return new SchedulerStatusResponse.PipelineStatusSummary(
                run.id,
                run.tradeDate == null ? null : run.tradeDate.toString(),
                nullToEmpty(run.status),
                run.currentStep,
                progress,
                processed,
                completed,
                success,
                failed,
                primaryFailureReason(run),
                formatDateTime(run.nextRetryAt),
                formatDateTime(started),
                formatDateTime(run.finishedAt),
                duration,
                pipelineRunMessage(run, pipelineName)
        );
    }

    private List<SchedulerStatusResponse.PipelineTrendEntry> buildRecentTrend(
            List<AiPipelineRun> globalRuns,
            List<AiPipelineRun> userRuns
    ) {
        Map<LocalDate, AiPipelineRun> globalByDate = latestByTradeDate(globalRuns);
        Map<LocalDate, AiPipelineRun> userByDate = latestByTradeDate(userRuns);
        return java.util.stream.Stream.concat(globalByDate.keySet().stream(), userByDate.keySet().stream())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .limit(7)
                .map(date -> {
                    AiPipelineRun global = globalByDate.get(date);
                    AiPipelineRun user = userByDate.get(date);
                    String reason = primaryFailureReason(global);
                    if (reason == null) {
                        reason = primaryFailureReason(user);
                    }
                    return new SchedulerStatusResponse.PipelineTrendEntry(
                            date.toString(),
                            statusOrNull(global),
                            statusOrNull(user),
                            global == null ? null : nonNegative(global.failedCount),
                            user == null ? null : nonNegative(user.failedCount),
                            durationMillis(global),
                            durationMillis(user),
                            reason
                    );
                })
                .toList();
    }

    private static Map<LocalDate, AiPipelineRun> latestByTradeDate(List<AiPipelineRun> runs) {
        Map<LocalDate, AiPipelineRun> result = new LinkedHashMap<>();
        if (runs == null) {
            return result;
        }
        for (AiPipelineRun run : runs) {
            if (run != null && run.tradeDate != null) {
                result.putIfAbsent(run.tradeDate, run);
            }
        }
        return result;
    }

    private static String statusOrNull(AiPipelineRun run) {
        return run == null ? null : nullToEmpty(run.status);
    }

    private static long durationMillis(AiPipelineRun run) {
        if (run == null) {
            return 0;
        }
        LocalDateTime started = run.startedAt == null ? run.createdAt : run.startedAt;
        LocalDateTime ended = run.finishedAt == null ? LocalDateTime.now() : run.finishedAt;
        return started == null ? 0 : Math.max(0, java.time.Duration.between(started, ended).toMillis());
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static String primaryFailureReason(AiPipelineRun run) {
        if (run == null) {
            return null;
        }
        String message = firstMeaningfulLine(run.errorMessage);
        if (message != null) {
            return message;
        }
        return firstMeaningfulLine(run.errorDetail);
    }

    private static String firstMeaningfulLine(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 237) + "...";
    }

    private PipelineStatusView latestPipelineStatus(Long userId, boolean automationEnabled) {
        AiPipelineRun latest = pipelineRunMapper.selectOne(new QueryWrapper<AiPipelineRun>()
                .eq("scope_type", "USER")
                .eq("owner_user_id", userId)
                .eq("pipeline_type", "USER_DAILY_PROJECTION")
                .orderByDesc("created_at", "id")
                .last("LIMIT 1"));
        if (latest != null) {
            return pipelineStatusView(latest, "用户投研日报投影流水线");
        }
        AiPipelineRun global = pipelineRunMapper.selectOne(new QueryWrapper<AiPipelineRun>()
                .eq("scope_type", "GLOBAL")
                .eq("pipeline_type", "GLOBAL_DAILY_RESEARCH")
                .orderByDesc("created_at", "id")
                .last("LIMIT 1"));
        if (global != null) {
            return pipelineStatusView(global, "全局日度研究流水线");
        }
        return automationEnabled
                ? new PipelineStatusView("IDLE", "等待下一个交易日 16:00 自动运行", null, null)
                : new PipelineStatusView("DISABLED", "每日自动收盘投研流水线已关闭", null, null);
    }

    @GetMapping("/scheduler/job-logs")
    public ApiResponse<List<SchedulerJobLogResponse>> schedulerJobLogs(Integer limit) {
        int size = Math.max(1, Math.min(limit == null ? 20 : limit, 50));
        Long userId = AuthContext.currentUserId().orElseThrow(() ->
                new org.springframework.security.access.AccessDeniedException("请先登录"));
        List<AiPipelineRun> rows = pipelineRunMapper.selectList(new QueryWrapper<AiPipelineRun>()
                .and(scope -> scope.eq("scope_type", "GLOBAL")
                        .or(owner -> owner.eq("scope_type", "USER").eq("owner_user_id", userId)))
                .in("pipeline_type", "GLOBAL_DAILY_RESEARCH", "USER_DAILY_PROJECTION",
                        "GLOBAL_WEEKLY_RESEARCH", "GLOBAL_MONTHLY_TRAINING")
                .orderByDesc("created_at", "id")
                .last("LIMIT " + size));
        return ApiResponse.ok(rows.stream()
                .map(item -> new SchedulerJobLogResponse(
                        item.id,
                        pipelineName(item.pipelineType),
                        item.pipelineType,
                        item.status,
                        item.startedAt,
                        item.finishedAt,
                        item.processedCount,
                        item.successCount,
                        item.failedCount,
                        item.currentStep,
                        item.retryCount,
                        item.nextRetryAt,
                        item.errorMessage,
                        item.errorDetail
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

    private static PipelineStatusView pipelineStatusView(AiPipelineRun run, String pipelineName) {
        return new PipelineStatusView(
                nullToEmpty(run.status),
                pipelineRunMessage(run, pipelineName),
                run.startedAt == null ? run.createdAt : run.startedAt,
                run.finishedAt);
    }

    private static String pipelineRunMessage(AiPipelineRun run, String pipelineName) {
        if (run.errorMessage != null && !run.errorMessage.isBlank()) {
            return run.errorMessage;
        }
        return switch (nullToEmpty(run.status)) {
            case "SUCCESS" -> pipelineName + " #" + run.id + " 已完成";
            case "PARTIAL_SUCCESS" -> run.nextRetryAt == null
                    ? pipelineName + " #" + run.id + " 部分完成"
                    : pipelineName + " #" + run.id + " 已生成日报，失败的个股报告将在 "
                    + formatDateTime(run.nextRetryAt) + " 自动重试";
            case "WAITING_SOURCE" -> pipelineName + " #" + run.id + " 正在等待完整收盘数据，系统会自动重试";
            case "FAILED_RECOVERABLE" -> pipelineName + " #" + run.id + " 已识别为可恢复故障，系统正在自动重试";
            case "FAILED_FINAL" -> pipelineName + " #" + run.id + " 自动恢复已停止，请查看任务日志中的具体原因";
            case "RUNNING", "PENDING" -> pipelineName + " #" + run.id + " 正在执行";
            case "FAILED" -> pipelineName + " #" + run.id + " 执行失败，请查看任务日志";
            default -> pipelineName + " #" + run.id + " 状态：" + nullToEmpty(run.status);
        };
    }

    private record PipelineStatusView(
            String status,
            String message,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
    }

    private static String pipelineName(String type) {
        return switch (nullToEmpty(type)) {
            case "GLOBAL_DAILY_RESEARCH" -> "全局日度研究";
            case "USER_DAILY_PROJECTION" -> "用户投研日报投影";
            case "GLOBAL_WEEKLY_RESEARCH" -> "全局周度策略研究";
            case "GLOBAL_MONTHLY_TRAINING" -> "全局月度模型训练";
            default -> "研究流水线";
        };
    }
}
