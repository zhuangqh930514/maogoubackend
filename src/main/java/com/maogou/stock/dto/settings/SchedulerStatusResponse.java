package com.maogou.stock.dto.settings;

import java.util.List;

public record SchedulerStatusResponse(
        boolean enabled,
        long newsFixedRateMs,
        long intradayAnalysisFixedRateMs,
        String closeAnalysisCron,
        String evolutionReviewCron,
        Integer intradayInterval,
        String closeTime,
        String analysisScope,
        String nextCloseAnalysisTime,
        String nextEvolutionReviewTime,
        boolean autoClosePipelineEnabled,
        boolean autoClosePipelineRunning,
        String autoClosePipelineCron,
        String nextAutoClosePipelineTime,
        String autoClosePipelineLastRunAt,
        String autoClosePipelineLastFinishedAt,
        String autoClosePipelineLastStatus,
        String autoClosePipelineLastMessage,
        String weeklyEvolutionCron,
        String nextWeeklyEvolutionTime,
        String monthlyTrainingCron,
        String nextMonthlyTrainingTime,
        ResearchDailyReportSummary latestResearchDailyReport,
        PipelineStatusSummary globalResearch,
        PipelineStatusSummary userDailyReport,
        List<PipelineTrendEntry> recentTradingDayTrend
) {

    public SchedulerStatusResponse {
        recentTradingDayTrend = recentTradingDayTrend == null ? List.of() : List.copyOf(recentTradingDayTrend);
    }

    public record ResearchDailyReportSummary(
            Long id,
            String tradeDate,
            Integer reportVersion,
            String reportStatus,
            String title,
            String generatedAt,
            Integer recommendationCount,
            Integer watchCount,
            Integer avoidCount,
            String freshnessStatus
    ) {
    }

    /**
     * Read-only status for one pipeline scope. Counts are persisted evidence, not an estimate of
     * the number of stocks that should have been processed.
     */
    public record PipelineStatusSummary(
            Long runId,
            String tradeDate,
            String status,
            String currentStep,
            Integer progressPercent,
            Integer processedCount,
            Integer completedCount,
            Integer successCount,
            Integer failedCount,
            String primaryFailureReason,
            String nextRetryAt,
            String startedAt,
            String finishedAt,
            Long durationMillis,
            String message
    ) {
    }

    public record PipelineTrendEntry(
            String tradeDate,
            String globalStatus,
            String userDailyReportStatus,
            Integer globalFailedCount,
            Integer userDailyReportFailedCount,
            Long globalDurationMillis,
            Long userDailyReportDurationMillis,
            String primaryFailureReason
    ) {
    }
}
