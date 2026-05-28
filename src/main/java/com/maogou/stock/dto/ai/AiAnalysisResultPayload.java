package com.maogou.stock.dto.ai;

import java.util.List;

public record AiAnalysisResultPayload(
        TechnicalAnalysisPayload technicalAnalysis,
        RiskWarningPayload riskWarning,
        BuySellPointsPayload buySellPoints,
        PromptSummaryPayload promptSummary,
        Integer score
) {
    public record TechnicalAnalysisPayload(
            String trendAssessment,
            TrendPayload trend,
            MovingAveragesPayload movingAverages,
            KlinePatternPayload klinePattern,
            SupportResistancePayload supportResistance,
            String volumeAnalysis,
            String signal,
            String description
    ) {
    }

    public record TrendPayload(
            String shortTerm,
            String mediumTerm
    ) {
    }

    public record MovingAveragesPayload(
            String currentPrice,
            String ma5,
            String ma10,
            String ma20,
            String ma30,
            String ma60,
            String bias
    ) {
    }

    public record KlinePatternPayload(
            String patternName,
            String description
    ) {
    }

    public record SupportResistancePayload(
            List<String> support,
            List<String> resistance,
            String nearestSupport,
            String nearestResistance
    ) {
    }

    public record RiskWarningPayload(
            String headline,
            List<String> currentRisks,
            List<String> triggerConditions,
            List<String> observationPoints,
            String overallAdvice
    ) {
    }

    public record BuySellPointsPayload(
            String action,
            List<String> buyTriggers,
            List<String> reduceTriggers,
            String stopLoss,
            String invalidationCondition,
            String positionSuggestion
    ) {
    }

    public record PromptSummaryPayload(
            String marketSnapshot,
            String valuationSnapshot,
            String growthSnapshot,
            String klineSummary,
            String volumeSummary
    ) {
    }
}
