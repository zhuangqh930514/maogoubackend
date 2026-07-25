package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiSourceHealth;
import com.maogou.stock.dto.research.ResearchOperationsOverviewPayloads;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiResearchOperationsOverviewMapper;
import com.maogou.stock.mapper.research.AiSourceHealthMapper;
import com.maogou.stock.service.research.AiResearchOperationsOverviewService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Operator-facing projection over persisted evidence. The implementation deliberately does not
 * derive a success state from a cache, a missing record, or a guessed security status.
 */
@Service
public class AiResearchOperationsOverviewServiceImpl implements AiResearchOperationsOverviewService {

    private static final int DEFAULT_WINDOW_DAYS = 14;
    private static final int MIN_WINDOW_DAYS = 1;
    private static final int MAX_WINDOW_DAYS = 90;
    private static final int EVIDENCE_LIMIT = 100;
    private static final int ALERT_LIMIT = 100;
    private static final Pattern STOCK_CODE = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");
    private static final Pattern PROVIDER = Pattern.compile(
            "(?:数据提供方|provider(?:Code)?|source(?:Provider)?)[\\s:=：]+([^；;，,\\s]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> HEALTHY_SOURCE_STATUSES = Set.of("HEALTHY", "READY", "REALTIME");

    private final AiResearchOperationsOverviewMapper overviewMapper;
    private final AiPipelineRunMapper pipelineRunMapper;
    private final AiSourceHealthMapper sourceHealthMapper;
    private final AiDataBatchMapper dataBatchMapper;

    public AiResearchOperationsOverviewServiceImpl(
            AiResearchOperationsOverviewMapper overviewMapper,
            AiPipelineRunMapper pipelineRunMapper,
            AiSourceHealthMapper sourceHealthMapper,
            AiDataBatchMapper dataBatchMapper
    ) {
        this.overviewMapper = overviewMapper;
        this.pipelineRunMapper = pipelineRunMapper;
        this.sourceHealthMapper = sourceHealthMapper;
        this.dataBatchMapper = dataBatchMapper;
    }

    @Override
    public ResearchOperationsOverviewPayloads.Overview overview(Integer requestedWindowDays) {
        int windowDays = clampWindow(requestedWindowDays);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(windowDays);
        AiPipelineRun latestGlobalRun = overviewMapper.selectLatestGlobalRun();
        LocalDate tradeDate = latestGlobalRun == null ? null : latestGlobalRun.tradeDate;

        List<AiResearchOperationsOverviewMapper.StatusCountRow> runStatusCounts =
                safe(overviewMapper.selectRunStatusCounts(since));
        List<AiPipelineRun> completedRuns = safe(overviewMapper.selectCompletedRuns(since, EVIDENCE_LIMIT));
        List<AiPipelineRun> staleRuns = safe(pipelineRunMapper.selectStaleRunning(
                now.minusMinutes(30), now, EVIDENCE_LIMIT));
        List<AiPipelineRun> attentionRuns = mergeRunEvidence(
                staleRuns, safe(overviewMapper.selectAttentionRuns(since, EVIDENCE_LIMIT)));

        ResearchOperationsOverviewPayloads.TaskSummary tasks = new ResearchOperationsOverviewPayloads.TaskSummary(
                sumCounts(runStatusCounts),
                statusCounts(runStatusCounts),
                percentileMillis(completedRuns, 0.50d),
                percentileMillis(completedRuns, 0.95d),
                staleRuns.size(),
                attentionRuns.stream().map(this::runEvidence).toList());

        List<AiSourceHealth> sourceHealth = safe(sourceHealthMapper.selectList(
                new QueryWrapper<AiSourceHealth>().orderByAsc("source_status").orderByAsc("provider_code", "endpoint_type")));
        List<ResearchOperationsOverviewPayloads.SourceHealth> sourceItems = sourceHealth.stream()
                .map(this::sourceHealth).toList();
        List<ResearchOperationsOverviewPayloads.Coverage> coverage = loadCoverage(latestGlobalRun);

        List<AiAnalysisReport> modelFailureRecords = safe(overviewMapper.selectRecentModelFailures(since, EVIDENCE_LIMIT));
        List<ResearchOperationsOverviewPayloads.ModelFailure> modelFailures = modelFailureRecords.stream()
                .map(this::modelFailure).toList();
        Map<String, Long> failureGroups = failureGroups(modelFailures);

        ResearchOperationsOverviewPayloads.DailyReportCoverage dailyReports = dailyReportCoverage(tradeDate);
        ResearchOperationsOverviewPayloads.HoldingCoverage holdings = holdingCoverage(tradeDate);
        ResearchOperationsOverviewPayloads.DecisionConflictSummary conflicts = decisionConflicts(tradeDate);
        ResearchOperationsOverviewPayloads.UniversePollutionSummary pollution = universePollution(latestGlobalRun);
        ResearchOperationsOverviewPayloads.UniverseLineageSummary lineage = universeLineage(latestGlobalRun);

        List<ResearchOperationsOverviewPayloads.Alert> alerts = alerts(
                staleRuns, attentionRuns, sourceHealth, modelFailures, dailyReports, holdings, conflicts, pollution, lineage);
        return new ResearchOperationsOverviewPayloads.Overview(
                now, tradeDate, windowDays, tasks,
                new ResearchOperationsOverviewPayloads.SourceSummary(sourceItems, coverage),
                new ResearchOperationsOverviewPayloads.ModelFailureSummary(
                        overviewMapper.selectModelFailureCount(since), failureGroups, modelFailures),
                dailyReports, holdings, conflicts, pollution, lineage, alerts);
    }

    private List<ResearchOperationsOverviewPayloads.Coverage> loadCoverage(AiPipelineRun latestGlobalRun) {
        if (latestGlobalRun == null || latestGlobalRun.dataBatchId == null) {
            return List.of();
        }
        return safe(overviewMapper.selectSampleCoverage(latestGlobalRun.dataBatchId)).stream()
                .map(row -> new ResearchOperationsOverviewPayloads.Coverage(normalized(row.status, "UNAVAILABLE"), count(row.recordCount)))
                .sorted(Comparator.comparing(ResearchOperationsOverviewPayloads.Coverage::status))
                .toList();
    }

    private ResearchOperationsOverviewPayloads.DailyReportCoverage dailyReportCoverage(LocalDate tradeDate) {
        if (tradeDate == null) {
            return new ResearchOperationsOverviewPayloads.DailyReportCoverage(0, 0, 0, List.of());
        }
        long eligible = overviewMapper.selectEligibleUserCount();
        List<ResearchOperationsOverviewPayloads.UserReportGap> gaps = safe(
                overviewMapper.selectUsersMissingDailyReport(tradeDate, EVIDENCE_LIMIT)).stream()
                .map(row -> new ResearchOperationsOverviewPayloads.UserReportGap(
                        row.userId, blankToFallback(row.displayName, "用户 #" + row.userId),
                        truthy(row.hasWatchlist), truthy(row.hasHolding)))
                .toList();
        List<ResearchOperationsOverviewPayloads.ConsecutiveReportGap> consecutive = safe(
                overviewMapper.selectUsersMissingTwoLatestDailyReports(EVIDENCE_LIMIT)).stream()
                .map(row -> new ResearchOperationsOverviewPayloads.ConsecutiveReportGap(
                        row.userId, blankToFallback(row.displayName, "用户 #" + row.userId), row.missingTradeDates))
                .toList();
        long missing = overviewMapper.selectMissingDailyReportUserCount(tradeDate);
        return new ResearchOperationsOverviewPayloads.DailyReportCoverage(
                eligible, Math.max(0, eligible - missing), missing, gaps, consecutive);
    }

    private ResearchOperationsOverviewPayloads.HoldingCoverage holdingCoverage(LocalDate tradeDate) {
        if (tradeDate == null) {
            return new ResearchOperationsOverviewPayloads.HoldingCoverage(0, 0, List.of());
        }
        long activeCount = overviewMapper.selectActiveHoldingCount();
        long missingCount = overviewMapper.selectHoldingWithoutDailyConclusionCount(tradeDate);
        List<ResearchOperationsOverviewPayloads.HoldingGap> gaps = safe(
                overviewMapper.selectHoldingsWithoutDailyConclusion(tradeDate, EVIDENCE_LIMIT)).stream()
                .map(row -> new ResearchOperationsOverviewPayloads.HoldingGap(
                        row.userId, row.stockCode, blankToFallback(row.stockName, row.stockCode), count(row.netQuantity)))
                .toList();
        return new ResearchOperationsOverviewPayloads.HoldingCoverage(activeCount, missingCount, gaps);
    }

    private ResearchOperationsOverviewPayloads.DecisionConflictSummary decisionConflicts(LocalDate tradeDate) {
        if (tradeDate == null) {
            return new ResearchOperationsOverviewPayloads.DecisionConflictSummary(0, 0, List.of());
        }
        long conflictCount = overviewMapper.selectDecisionConflictCount(tradeDate);
        long withoutReportCount = overviewMapper.selectDailyDecisionWithoutReportCount(tradeDate);
        List<ResearchOperationsOverviewPayloads.DecisionConflict> conflicts = safe(
                overviewMapper.selectDecisionConflicts(tradeDate, EVIDENCE_LIMIT)).stream()
                .map(row -> new ResearchOperationsOverviewPayloads.DecisionConflict(
                        row.userId, row.decisionItemId, row.reportId, row.stockCode,
                        blankToFallback(row.stockName, row.stockCode), row.decisionAction, row.reportAction))
                .toList();
        return new ResearchOperationsOverviewPayloads.DecisionConflictSummary(conflictCount, withoutReportCount, conflicts);
    }

    private ResearchOperationsOverviewPayloads.UniversePollutionSummary universePollution(AiPipelineRun latestGlobalRun) {
        if (latestGlobalRun == null || latestGlobalRun.dataBatchId == null) {
            return new ResearchOperationsOverviewPayloads.UniversePollutionSummary(0, List.of());
        }
        AiDataBatch batch = dataBatchMapper.selectById(latestGlobalRun.dataBatchId);
        if (batch == null || batch.universeSnapshotId == null) {
            return new ResearchOperationsOverviewPayloads.UniversePollutionSummary(0, List.of());
        }
        long count = overviewMapper.selectUniversePollutionCount(batch.universeSnapshotId, batch.id);
        List<ResearchOperationsOverviewPayloads.UniversePollution> items = safe(
                overviewMapper.selectUniversePollutionItems(batch.universeSnapshotId, batch.id, EVIDENCE_LIMIT)).stream()
                .map(row -> new ResearchOperationsOverviewPayloads.UniversePollution(
                        row.universeItemId, row.stockCode, blankToFallback(row.stockName, row.stockCode),
                        row.sourceType, row.listedStatus, row.qualityStatus, row.tradableStatus,
                        row.issueType, row.cause))
                .toList();
        return new ResearchOperationsOverviewPayloads.UniversePollutionSummary(count, items);
    }

    private ResearchOperationsOverviewPayloads.UniverseLineageSummary universeLineage(AiPipelineRun latestGlobalRun) {
        if (latestGlobalRun == null || latestGlobalRun.dataBatchId == null) {
            return new ResearchOperationsOverviewPayloads.UniverseLineageSummary(0, 0, List.of());
        }
        AiDataBatch batch = dataBatchMapper.selectById(latestGlobalRun.dataBatchId);
        if (batch == null || batch.universeSnapshotId == null) {
            return new ResearchOperationsOverviewPayloads.UniverseLineageSummary(0, 0, List.of());
        }
        long recorded = overviewMapper.selectUniverseLineageCount(batch.universeSnapshotId);
        long invalid = overviewMapper.selectInvalidUniverseLineageCount(batch.universeSnapshotId);
        List<ResearchOperationsOverviewPayloads.UniverseLineageIssue> items = safe(
                overviewMapper.selectInvalidUniverseLineages(batch.universeSnapshotId, EVIDENCE_LIMIT)).stream()
                .map(row -> new ResearchOperationsOverviewPayloads.UniverseLineageIssue(
                        row.universeItemId, row.stockCode, blankToFallback(row.stockName, row.stockCode),
                        row.sourceType, row.ownerUserId, row.sourceRecordId, row.activeAtSnapshot, row.cause))
                .toList();
        return new ResearchOperationsOverviewPayloads.UniverseLineageSummary(recorded, invalid, items);
    }

    private List<ResearchOperationsOverviewPayloads.Alert> alerts(
            List<AiPipelineRun> staleRuns,
            List<AiPipelineRun> attentionRuns,
            List<AiSourceHealth> sourceHealth,
            List<ResearchOperationsOverviewPayloads.ModelFailure> modelFailures,
            ResearchOperationsOverviewPayloads.DailyReportCoverage dailyReports,
            ResearchOperationsOverviewPayloads.HoldingCoverage holdings,
            ResearchOperationsOverviewPayloads.DecisionConflictSummary conflicts,
            ResearchOperationsOverviewPayloads.UniversePollutionSummary pollution,
            ResearchOperationsOverviewPayloads.UniverseLineageSummary lineage
    ) {
        List<ResearchOperationsOverviewPayloads.Alert> result = new ArrayList<>();
        Set<Long> staleIds = new HashSet<>();
        for (AiPipelineRun run : staleRuns) {
            staleIds.add(run.id);
            result.add(runAlert("CRITICAL", "STALE_RUNNING", "运行租约已失效", run,
                    "任务超过 30 分钟未更新且没有有效租约，等待自动恢复或人工检查"));
        }
        for (AiPipelineRun run : attentionRuns) {
            if (run.id != null && staleIds.contains(run.id)) {
                continue;
            }
            result.add(runAlert("WARNING", "PIPELINE_ATTENTION", "流水线需要关注", run, runCause(run)));
        }
        for (AiSourceHealth source : sourceHealth) {
            if (!sourceNeedsAttention(source)) {
                continue;
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("endpointType", source.endpointType);
            context.put("lastSuccessAt", source.lastSuccessAt);
            context.put("cooldownUntil", source.cooldownUntil);
            result.add(new ResearchOperationsOverviewPayloads.Alert(
                    "source:" + source.id, "WARNING", "SOURCE_HEALTH", "数据源状态异常",
                    null, null, null, source.providerCode, source.lastErrorMessage,
                    source.consecutiveFailureCount, source.cooldownUntil, context));
        }
        for (ResearchOperationsOverviewPayloads.ModelFailure failure : modelFailures) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("reportId", failure.reportId());
            context.put("userId", failure.userId());
            context.put("failureType", failure.failureType());
            result.add(new ResearchOperationsOverviewPayloads.Alert(
                    "model:" + failure.reportId(), "WARNING", "MODEL_FAILURE", "模型调用失败",
                    null, "GENERATE_STOCK_REPORTS", failure.stockCode(), failure.sourceModel(), failure.cause(),
                    null, null, immutableContext(context)));
        }
        for (ResearchOperationsOverviewPayloads.UserReportGap gap : dailyReports.missingUsers()) {
            result.add(new ResearchOperationsOverviewPayloads.Alert(
                    "report-gap:" + gap.userId(), "WARNING", "DAILY_REPORT_MISSING", "用户日报缺失",
                    null, "ARCHIVE_RESEARCH_REPORT", null, null,
                    "用户存在自选股或真实持仓，但未生成当前交易日投研日报", null, null,
                    context("userId", gap.userId(), "displayName", gap.displayName(),
                            "hasWatchlist", gap.hasWatchlist(), "hasHolding", gap.hasHolding())));
        }
        for (ResearchOperationsOverviewPayloads.ConsecutiveReportGap gap : dailyReports.consecutiveMissingUsers()) {
            result.add(new ResearchOperationsOverviewPayloads.Alert(
                    "report-gap-consecutive:" + gap.userId(), "CRITICAL", "DAILY_REPORT_MISSING_CONSECUTIVE",
                    "连续两个已完成交易日缺少日报", null, "ARCHIVE_RESEARCH_REPORT", null, null,
                    "用户在最近两个已完成全局研究交易日均未生成正式投研日报", null, null,
                    context("userId", gap.userId(), "displayName", gap.displayName(),
                            "missingTradeDates", normalized(gap.missingTradeDates(), "未记录"))));
        }
        for (ResearchOperationsOverviewPayloads.HoldingGap gap : holdings.gaps()) {
            result.add(new ResearchOperationsOverviewPayloads.Alert(
                    "holding-gap:" + gap.userId() + ":" + gap.stockCode(), "CRITICAL",
                    "HOLDING_CONCLUSION_MISSING", "持仓缺少当日结论", null,
                    "BUILD_DAILY_DECISION", gap.stockCode(), null,
                    "真实持仓未找到当前交易日正式决策", null, null,
                    context("userId", gap.userId(), "stockName", gap.stockName(), "netQuantity", gap.netQuantity())));
        }
        for (ResearchOperationsOverviewPayloads.DecisionConflict conflict : conflicts.conflicts()) {
            result.add(new ResearchOperationsOverviewPayloads.Alert(
                    "decision-conflict:" + conflict.decisionItemId(), "WARNING", "DECISION_CONFLICT",
                    "日报结论与关联 AI 报告不一致", null, "BUILD_DAILY_DECISION",
                    conflict.stockCode(), null, "正式决策与关联报告动作不同，需检查裁决证据", null, null,
                    context("userId", conflict.userId(), "decisionItemId", conflict.decisionItemId(),
                            "reportId", conflict.reportId(), "decisionAction", conflict.decisionAction(),
                            "reportAction", conflict.reportAction())));
        }
        for (ResearchOperationsOverviewPayloads.UniversePollution item : pollution.items()) {
            result.add(new ResearchOperationsOverviewPayloads.Alert(
                    "universe:" + item.universeItemId(), "CRITICAL", "UNIVERSE_POLLUTION",
                    "研究池发现不应入池证券", null, "BUILD_UNIVERSE", item.stockCode(), null,
                    item.cause(), null, null,
                    context("universeItemId", item.universeItemId(), "issueType", item.issueType(),
                            "sourceType", normalized(item.sourceType(), "未记录"),
                            "listedStatus", normalized(item.listedStatus(), "未记录"),
                            "qualityStatus", normalized(item.qualityStatus(), "未记录"),
                            "tradableStatus", normalized(item.tradableStatus(), "未记录"))));
        }
        for (ResearchOperationsOverviewPayloads.UniverseLineageIssue item : lineage.items()) {
            result.add(new ResearchOperationsOverviewPayloads.Alert(
                    "universe-lineage:" + item.universeItemId() + ":" + item.sourceType() + ":" + item.sourceRecordId(),
                    "CRITICAL", "UNIVERSE_LINEAGE_INVALID", "研究池来源血缘无效", null, "BUILD_UNIVERSE",
                    item.stockCode(), item.sourceType(), item.cause(), null, null,
                    context("universeItemId", item.universeItemId(), "ownerUserId", item.ownerUserId(),
                            "sourceRecordId", item.sourceRecordId(), "activeAtSnapshot", item.activeAtSnapshot())));
        }
        return result.stream()
                .sorted(Comparator.comparingInt((ResearchOperationsOverviewPayloads.Alert alert) -> severityRank(alert.severity()))
                        .thenComparing(ResearchOperationsOverviewPayloads.Alert::category)
                        .thenComparing(ResearchOperationsOverviewPayloads.Alert::id))
                .limit(ALERT_LIMIT)
                .toList();
    }

    private ResearchOperationsOverviewPayloads.Alert runAlert(
            String severity, String category, String title, AiPipelineRun run, String cause) {
        String detail = join(run.errorMessage, run.errorDetail, cause);
        return new ResearchOperationsOverviewPayloads.Alert(
                "run:" + run.id + ":" + category, severity, category, title,
                run.id, run.currentStep, extractStockCode(detail), extractProvider(detail),
                detail, value(run.retryCount), run.nextRetryAt,
                context("scopeType", normalized(run.scopeType, "未记录"),
                        "ownerUserId", run.ownerUserId == null ? "未记录" : run.ownerUserId,
                        "pipelineType", normalized(run.pipelineType, "未记录"),
                        "status", normalized(run.status, "未记录"),
                        "processedCount", value(run.processedCount),
                        "successCount", value(run.successCount),
                        "failedCount", value(run.failedCount),
                        "leaseUntil", run.leaseUntil == null ? "未记录" : run.leaseUntil.toString()));
    }

    private List<AiPipelineRun> mergeRunEvidence(List<AiPipelineRun> first, List<AiPipelineRun> second) {
        Map<Long, AiPipelineRun> distinct = new LinkedHashMap<>();
        for (AiPipelineRun run : first) {
            if (run != null && run.id != null) {
                distinct.put(run.id, run);
            }
        }
        for (AiPipelineRun run : second) {
            if (run != null && run.id != null) {
                distinct.putIfAbsent(run.id, run);
            }
        }
        return distinct.values().stream().limit(EVIDENCE_LIMIT).toList();
    }

    private ResearchOperationsOverviewPayloads.RunEvidence runEvidence(AiPipelineRun run) {
        return new ResearchOperationsOverviewPayloads.RunEvidence(
                run.id, run.scopeType, run.ownerUserId, run.tradeDate, run.pipelineType, run.status,
                run.currentStep, run.processedCount, run.successCount, run.failedCount,
                run.retryCount, run.nextRetryAt, run.leaseUntil, run.updatedAt, runCause(run));
    }

    private ResearchOperationsOverviewPayloads.SourceHealth sourceHealth(AiSourceHealth source) {
        return new ResearchOperationsOverviewPayloads.SourceHealth(
                source.id, source.providerCode, source.endpointType, source.sourceStatus,
                source.lastAttemptAt, source.lastSuccessAt, source.consecutiveFailureCount,
                source.cooldownUntil, source.lastErrorMessage);
    }

    private ResearchOperationsOverviewPayloads.ModelFailure modelFailure(AiAnalysisReport report) {
        String cause = normalized(report.errorMessage, "未记录模型错误详情");
        return new ResearchOperationsOverviewPayloads.ModelFailure(
                report.id, report.userId, report.stockCode, report.sourceModel,
                classifyModelFailure(cause), cause, report.generatedAt);
    }

    private static Map<String, Long> statusCounts(List<AiResearchOperationsOverviewMapper.StatusCountRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AiResearchOperationsOverviewMapper.StatusCountRow row : safe(rows)) {
            counts.put(normalized(row.status, "UNKNOWN"), count(row.recordCount));
        }
        return counts;
    }

    private static long sumCounts(List<AiResearchOperationsOverviewMapper.StatusCountRow> rows) {
        return safe(rows).stream().mapToLong(row -> count(row.recordCount)).sum();
    }

    private static Long percentileMillis(List<AiPipelineRun> runs, double percentile) {
        List<Long> latencies = safe(runs).stream()
                .filter(run -> run.startedAt != null && run.finishedAt != null && !run.finishedAt.isBefore(run.startedAt))
                .map(run -> Duration.between(run.startedAt, run.finishedAt).toMillis())
                .sorted()
                .toList();
        if (latencies.isEmpty()) {
            return null;
        }
        int index = Math.max(0, Math.min(latencies.size() - 1,
                (int) Math.ceil(percentile * latencies.size()) - 1));
        return latencies.get(index);
    }

    private static Map<String, Long> failureGroups(List<ResearchOperationsOverviewPayloads.ModelFailure> failures) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (ResearchOperationsOverviewPayloads.ModelFailure failure : failures) {
            result.merge(failure.failureType(), 1L, Long::sum);
        }
        return result;
    }

    static String classifyModelFailure(String cause) {
        String value = cause == null ? "" : cause.toLowerCase(Locale.ROOT);
        if (value.contains("429") || value.contains("rate limit") || value.contains("限流")) {
            return "RATE_LIMIT";
        }
        if (value.contains("401") || value.contains("403") || value.contains("unauthorized")
                || value.contains("forbidden") || value.contains("鉴权") || value.contains("api key")) {
            return "AUTHORIZATION";
        }
        if (value.contains("timeout") || value.contains("timed out") || value.contains("超时")) {
            return "TIMEOUT";
        }
        if (value.contains("connection") || value.contains("network") || value.contains("i/o error")
                || value.contains("eof") || value.contains("dns") || value.contains("连接") || value.contains("网络")) {
            return "NETWORK";
        }
        if (value.contains("json") || value.contains("parse") || value.contains("结构化")
                || value.contains("响应格式") || value.contains("invalid response")) {
            return "STRUCTURE";
        }
        return "UNKNOWN";
    }

    private static boolean sourceNeedsAttention(AiSourceHealth source) {
        return source != null && (!HEALTHY_SOURCE_STATUSES.contains(normalized(source.sourceStatus, "UNAVAILABLE"))
                || value(source.consecutiveFailureCount) > 0);
    }

    private static String runCause(AiPipelineRun run) {
        if (run == null) {
            return "未记录";
        }
        return normalized(join(run.errorMessage, run.errorDetail), "未记录失败原因");
    }

    private static String extractStockCode(String text) {
        Matcher matcher = STOCK_CODE.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String extractProvider(String text) {
        Matcher matcher = PROVIDER.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String join(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .reduce((left, right) -> left + "；" + right)
                .map(AiResearchOperationsOverviewServiceImpl::truncate)
                .orElse("");
    }

    private static String truncate(String value) {
        return value.length() <= 1200 ? value : value.substring(0, 1200) + "...";
    }

    private static int clampWindow(Integer requested) {
        int value = requested == null ? DEFAULT_WINDOW_DAYS : requested;
        return Math.max(MIN_WINDOW_DAYS, Math.min(MAX_WINDOW_DAYS, value));
    }

    private static int severityRank(String severity) {
        return "CRITICAL".equals(severity) ? 0 : "WARNING".equals(severity) ? 1 : 2;
    }

    private static boolean truthy(Integer value) {
        return value != null && value != 0;
    }

    private static long count(Long value) {
        return value == null ? 0L : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, Object> context(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            String key = String.valueOf(entries[index]);
            Object value = entries[index + 1];
            result.put(key, value == null ? "未记录" : value);
        }
        return result;
    }

    private static Map<String, Object> immutableContext(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? "未记录" : entry.getValue());
        }
        return result;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
