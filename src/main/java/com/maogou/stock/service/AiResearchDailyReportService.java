package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiResearchDailyReportService {

    ReportView generate(GenerationRequest request);

    ReportView latest();

    ReportView latestOrNull(Long userId);

    List<AiResearchDailyReportPayloads.ReportListItem> list(int limit);

    ReportListPage pageHistory(ReportListQuery query);

    DailyOverview overview(int historyLimit);

    DecisionItemPage pageItems(Long reportId, DecisionItemQuery query);

    ReportView detail(Long reportId);

    ReportView rebuildToday();

    ReportView rebuild(LocalDate tradeDate);

    record GenerationRequest(
            Long userId,
            LocalDate tradeDate,
            Long decisionSnapshotId,
            Long pipelineRunId,
            Long strategyReleaseId,
            Long modelVersionId,
            String idempotencyKey,
            String pipelineStatus,
            String failedStep,
            String pipelineMessage,
            LocalDateTime generatedAt
    ) {
        public GenerationRequest(
                Long userId,
                LocalDate tradeDate,
                Long pipelineRunId,
                Long strategyReleaseId,
                Long modelVersionId,
                String idempotencyKey,
                String pipelineStatus,
                String failedStep,
                String pipelineMessage,
                LocalDateTime generatedAt
        ) {
            this(userId, tradeDate, null, pipelineRunId, strategyReleaseId, modelVersionId,
                    idempotencyKey, pipelineStatus, failedStep, pipelineMessage, generatedAt);
        }
    }

    record ReportView(
            Long id,
            Long decisionSnapshotId,
            LocalDate tradeDate,
            Integer reportVersion,
            Long pipelineRunId,
            Long strategyReleaseId,
            Long modelVersionId,
            Long supersedesReportId,
            boolean current,
            String reportStatus,
            String title,
            String executiveSummary,
            String marketRegime,
            Integer recommendationCount,
            Integer watchCount,
            Integer avoidCount,
            Integer holdingRiskCount,
            String freshnessStatus,
            java.math.BigDecimal dataQualityScore,
            AiResearchDailyReportPayloads.ReportContent content,
            String markdownContent,
            LocalDateTime generatedAt
    ) {
        public static ReportView from(AiResearchDailyReportPayloads.ReportView view) {
            return new ReportView(
                    view.id(), view.decisionSnapshotId(), view.tradeDate(), view.reportVersion(),
                    view.pipelineRunId(),
                    view.strategyReleaseId(), view.modelVersionId(), view.supersedesReportId(),
                    view.current(), view.reportStatus(), view.title(), view.executiveSummary(),
                    view.marketRegime(), view.recommendationCount(), view.watchCount(),
                    view.avoidCount(), view.holdingRiskCount(), view.freshnessStatus(),
                    view.dataQualityScore(), view.content(), view.markdownContent(), view.generatedAt());
        }
    }

    record DailyOverview(
            ReportView report,
            List<AiResearchDailyReportPayloads.ReportListItem> history,
            List<DailyChange> dailyChanges,
            LocalDateTime nextAutoRunAt,
            ResearchRunSummary globalResearch,
            ResearchRunSummary userProjection
    ) {
    }

    record ResearchRunSummary(
            Long runId,
            String pipelineType,
            LocalDate tradeDate,
            String status,
            String currentStep,
            Integer processedCount,
            Integer successCount,
            Integer failedCount,
            Integer retryCount,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime nextRetryAt
    ) {
    }

    record ReportListQuery(LocalDate tradeDate, int page, int pageSize) {
        public ReportListQuery {
            page = Math.max(1, page);
            pageSize = Math.max(1, Math.min(pageSize, 30));
        }
    }

    record ReportListPage(
            List<AiResearchDailyReportPayloads.ReportListItem> items,
            long total,
            int page,
            int pageSize,
            int totalPages
    ) {
        public static ReportListPage empty(int page, int pageSize) {
            return new ReportListPage(List.of(), 0, Math.max(1, page), Math.max(1, pageSize), 0);
        }
    }

    record DailyChange(
            String stockCode,
            String stockName,
            String changeType,
            String previousAction,
            String currentAction,
            String previousCategory,
            String currentCategory,
            String message
    ) {
    }

    record DecisionItemQuery(
            String category,
            String action,
            String dataStatus,
            String keyword,
            String sort,
            int page,
            int pageSize
    ) {
        public DecisionItemQuery {
            page = Math.max(1, page);
            pageSize = Math.max(1, Math.min(pageSize, 50));
        }
    }

    record DecisionItemPage(
            List<AiResearchDailyReportPayloads.StockCard> items,
            long total,
            int page,
            int pageSize,
            int totalPages
    ) {
        public static DecisionItemPage empty(int page, int pageSize) {
            return new DecisionItemPage(List.of(), 0, Math.max(1, page), Math.max(1, pageSize), 0);
        }
    }
}
