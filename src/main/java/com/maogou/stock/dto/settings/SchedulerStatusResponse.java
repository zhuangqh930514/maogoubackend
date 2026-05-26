package com.maogou.stock.dto.settings;

public record SchedulerStatusResponse(
        boolean enabled,
        long newsFixedRateMs,
        long intradayAnalysisFixedRateMs,
        String closeAnalysisCron,
        Integer intradayInterval,
        String closeTime,
        String analysisScope,
        String nextCloseAnalysisTime
) {
}
