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

    ReportView detail(Long reportId);

    ReportView rebuildToday();

    record GenerationRequest(
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
    }

    record ReportView(
            Long id,
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
                    view.id(), view.tradeDate(), view.reportVersion(), view.pipelineRunId(),
                    view.strategyReleaseId(), view.modelVersionId(), view.supersedesReportId(),
                    view.current(), view.reportStatus(), view.title(), view.executiveSummary(),
                    view.marketRegime(), view.recommendationCount(), view.watchCount(),
                    view.avoidCount(), view.holdingRiskCount(), view.freshnessStatus(),
                    view.dataQualityScore(), view.content(), view.markdownContent(), view.generatedAt());
        }
    }
}
