package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;
import com.maogou.stock.domain.entity.research.AiPipelineIssue;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.domain.entity.research.AiResearchDailyReport;
import com.maogou.stock.mapper.research.AiDailyDecisionItemMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionSnapshotMapper;
import com.maogou.stock.mapper.research.AiPipelineIssueMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.mapper.research.AiResearchDailyReportMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportIssueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class AiResearchDailyReportIssueServiceImpl implements AiResearchDailyReportIssueService {
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final AiResearchDailyReportMapper reportMapper;
    private final AiDailyDecisionSnapshotMapper snapshotMapper;
    private final AiDailyDecisionItemMapper itemMapper;
    private final AiPipelineRunMapper pipelineRunMapper;
    private final AiPipelineStepMapper pipelineStepMapper;
    private final AiPipelineIssueMapper issueMapper;

    public AiResearchDailyReportIssueServiceImpl(
            AiResearchDailyReportMapper reportMapper,
            AiDailyDecisionSnapshotMapper snapshotMapper,
            AiDailyDecisionItemMapper itemMapper,
            AiPipelineRunMapper pipelineRunMapper,
            AiPipelineStepMapper pipelineStepMapper,
            AiPipelineIssueMapper issueMapper
    ) {
        this.reportMapper = reportMapper;
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.pipelineRunMapper = pipelineRunMapper;
        this.pipelineStepMapper = pipelineStepMapper;
        this.issueMapper = issueMapper;
    }

    @Override
    @Transactional
    public IssuePage page(Long reportId, int requestedPage, int requestedPageSize) {
        long userId = AuthContext.currentUserIdOrDefault();
        int pageSize = Math.max(1, Math.min(requestedPageSize, 50));
        int requested = Math.max(1, requestedPage);
        AiResearchDailyReport report = reportMapper.selectById(reportId);
        if (report == null || !Objects.equals(report.userId, userId)) {
            throw new IllegalArgumentException("日报不存在");
        }
        syncExistingFacts(userId, report);
        QueryWrapper<AiPipelineIssue> query = new QueryWrapper<AiPipelineIssue>()
                .eq("user_id", userId)
                .eq("report_id", reportId)
                .orderByDesc("created_at", "id");
        long total = issueMapper.selectCount(query);
        if (total == 0) {
            return IssuePage.empty(requested, pageSize);
        }
        int totalPages = (int) Math.ceil((double) total / pageSize);
        int page = Math.min(requested, totalPages);
        query.last("LIMIT " + pageSize + " OFFSET " + ((long) (page - 1) * pageSize));
        List<IssueView> items = issueMapper.selectList(query).stream().map(IssueView::from).toList();
        return new IssuePage(items, total, page, pageSize, totalPages);
    }

    private void syncExistingFacts(long userId, AiResearchDailyReport report) {
        LocalDateTime now = LocalDateTime.now();
        AiDailyDecisionSnapshot snapshot = report.decisionSnapshotId == null
                ? null : snapshotMapper.selectById(report.decisionSnapshotId);
        if (snapshot == null || !Objects.equals(snapshot.userId, userId)) {
            return;
        }
        recordRun(report, snapshot.globalPipelineRunId, "GLOBAL_DAILY_RESEARCH", now);
        recordRun(report, snapshot.pipelineRunId, "USER_DAILY_PROJECTION", now);
        for (Long runId : java.util.stream.Stream.of(snapshot.globalPipelineRunId, snapshot.pipelineRunId)
                .filter(Objects::nonNull).distinct().toList()) {
            for (AiPipelineStep step : pipelineStepMapper.selectByRunIdForUpdate(runId)) {
                if (hasFailure(step.status, step.errorMessage, step.errorDetail)) {
                    AiPipelineIssue issue = base(report, runId, report.tradeDate, step.stepKey,
                            step.stepKey, "", "", "", "", reasonCode(step.status),
                            firstText(step.errorMessage, step.errorDetail, "流水线步骤未完成"),
                            value(step.retryCount), DEFAULT_MAX_RETRIES, step.nextRetryAt,
                            recoverable(step.status), null, value(step.retryCount), now);
                    issueMapper.insertIgnore(issue);
                }
            }
        }
        for (AiDailyDecisionItem item : safeItems(userId, snapshot)) {
            if (!"UNAVAILABLE".equalsIgnoreCase(item.freshnessStatus)
                    && !"DATA_UNAVAILABLE".equalsIgnoreCase(item.category)) {
                continue;
            }
            AiPipelineIssue issue = base(report, snapshot.pipelineRunId == null ? 0 : snapshot.pipelineRunId,
                    report.tradeDate, "USER_DAILY_PROJECTION", "用户日报投影", item.stockCode,
                    item.stockName, "", "", "DATA_UNAVAILABLE",
                    firstText(item.unavailableReason, item.reasonSummary, "该股票没有可用的正式研究数据"),
                    0, DEFAULT_MAX_RETRIES, null, 1, null, 0, now);
            issueMapper.insertIgnore(issue);
        }
    }

    private void recordRun(AiResearchDailyReport report, Long runId, String stepKey, LocalDateTime now) {
        if (runId == null) {
            return;
        }
        AiPipelineRun run = pipelineRunMapper.selectById(runId);
        if (run == null || !hasFailure(run.status, run.errorMessage, run.errorDetail)
                && value(run.failedCount) == 0) {
            return;
        }
        AiPipelineIssue issue = base(report, run.id, report.tradeDate, stepKey,
                stepKey, "", "", "", "", reasonCode(run.status),
                firstText(run.errorMessage, run.errorDetail, "流水线未完整完成"),
                value(run.retryCount), DEFAULT_MAX_RETRIES, run.nextRetryAt,
                recoverable(run.status), null, value(run.retryCount), now);
        issueMapper.insertIgnore(issue);
    }

    private List<AiDailyDecisionItem> safeItems(long userId, AiDailyDecisionSnapshot snapshot) {
        return snapshot.id == null ? List.of() : itemMapper.selectBySnapshot(userId, snapshot.id);
    }

    private static AiPipelineIssue base(
            AiResearchDailyReport report, long runId, java.time.LocalDate tradeDate,
            String stepKey, String stepName, String stockCode, String stockName,
            String provider, String endpoint, String reasonCode, String reasonMessage,
            int retryCount, int maxRetries, java.time.LocalDateTime nextRetryAt,
            int recoverable, java.time.LocalDateTime sourceAsOf, int attemptNo, LocalDateTime now
    ) {
        AiPipelineIssue issue = new AiPipelineIssue();
        issue.userId = report.userId;
        issue.reportId = report.id;
        issue.pipelineRunId = runId;
        issue.tradeDate = tradeDate;
        issue.stepKey = blank(stepKey, "UNKNOWN_STEP");
        issue.stepName = blank(stepName, issue.stepKey);
        issue.stockCode = blank(stockCode, "");
        issue.stockName = blank(stockName, "");
        issue.providerCode = blank(provider, "");
        issue.endpointType = blank(endpoint, "");
        issue.reasonCode = blank(reasonCode, "UNKNOWN_FAILURE");
        issue.reasonMessage = blank(reasonMessage, "未记录具体原因");
        issue.retryCount = retryCount;
        issue.maxRetries = maxRetries;
        issue.nextRetryAt = nextRetryAt;
        issue.recoverable = recoverable;
        issue.sourceAsOf = sourceAsOf;
        issue.attemptNo = attemptNo;
        issue.createdAt = now;
        issue.updatedAt = now;
        return issue;
    }

    private static boolean hasFailure(String status, String message, String detail) {
        return (status != null && (status.contains("FAIL") || status.contains("WAITING") || "PARTIAL_SUCCESS".equals(status)))
                || (message != null && !message.isBlank()) || (detail != null && !detail.isBlank());
    }

    private static String reasonCode(String status) {
        if (status == null || status.isBlank()) return "PIPELINE_INCOMPLETE";
        return status.toUpperCase().contains("WAITING") ? "SOURCE_UNAVAILABLE" : "PIPELINE_" + status;
    }

    private static int recoverable(String status) {
        return status != null && (status.contains("WAITING") || status.contains("RECOVERABLE") || "PARTIAL_SUCCESS".equals(status)) ? 1 : 0;
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static String firstText(String first, String second, String fallback) {
        return first != null && !first.isBlank() ? first : second != null && !second.isBlank() ? second : fallback;
    }
    private static String blank(String value, String fallback) { return value == null ? fallback : value; }

}
