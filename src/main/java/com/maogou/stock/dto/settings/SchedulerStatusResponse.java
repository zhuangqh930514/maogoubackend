package com.maogou.stock.dto.settings;

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
        ResearchDailyReportSummary latestResearchDailyReport
) {

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
}
