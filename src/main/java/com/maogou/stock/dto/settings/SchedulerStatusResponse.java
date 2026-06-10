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
        String autoClosePipelineLastMessage
) {
}
