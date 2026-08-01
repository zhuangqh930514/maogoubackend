package com.maogou.stock.dto.ai;

import com.maogou.stock.domain.entity.AiAnalysisReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AiAnalysisReportResponse(
        Long id,
        String stock,
        String code,
        Integer score,
        String advice,
        LocalDateTime generatedAt,
        String technicalAnalysis,
        String riskWarning,
        String buySellPoints,
        String conditionalStrategy,
        List<AiConditionalStrategyPayload.ReviewResult> tradePlanReviews,
        String promptSummary,
        String sourceModel,
        String status,
        String errorMessage,
        Long sampleId,
        Long strategyReleaseId,
        Long pipelineRunId,
        String lineageStatus,
        String lineageIssueJson,
        Integer reportVersion,
        Long supersedesReportId,
        BigDecimal dataQualityScore,
        BigDecimal calibratedConfidence,
        String finalAction,
        DailyDecision dailyDecision
) {
    /**
     * Compatibility constructor for service tests and integrations created before report
     * lineage fields were exposed. The omitted lineage values are intentionally null.
     */
    public AiAnalysisReportResponse(
            Long id,
            String stock,
            String code,
            Integer score,
            String advice,
            LocalDateTime generatedAt,
            String technicalAnalysis,
            String riskWarning,
            String buySellPoints,
            String conditionalStrategy,
            List<AiConditionalStrategyPayload.ReviewResult> tradePlanReviews,
            String promptSummary,
            String sourceModel,
            String status,
            String errorMessage,
            Long sampleId,
            Long strategyReleaseId,
            Integer reportVersion,
            Long supersedesReportId,
            BigDecimal dataQualityScore,
            BigDecimal calibratedConfidence,
            String finalAction,
            DailyDecision dailyDecision
    ) {
        this(id, stock, code, score, advice, generatedAt, technicalAnalysis, riskWarning,
                buySellPoints, conditionalStrategy, tradePlanReviews, promptSummary, sourceModel,
                status, errorMessage, sampleId, strategyReleaseId, null, null, null,
                reportVersion, supersedesReportId, dataQualityScore, calibratedConfidence,
                finalAction, dailyDecision);
    }

    public static AiAnalysisReportResponse from(AiAnalysisReport entity) {
        return from(entity, List.of());
    }

    public static AiAnalysisReportResponse from(
            AiAnalysisReport entity,
            List<AiConditionalStrategyPayload.ReviewResult> tradePlanReviews
    ) {
        return from(entity, tradePlanReviews, null);
    }

    public static AiAnalysisReportResponse from(
            AiAnalysisReport entity,
            List<AiConditionalStrategyPayload.ReviewResult> tradePlanReviews,
            DailyDecision dailyDecision
    ) {
        String stockName = entity.stockName;
        if (stockName == null || stockName.isBlank() || "未知股票".equals(stockName.trim())) {
            stockName = entity.stockCode;
        }
        return new AiAnalysisReportResponse(
                entity.id,
                stockName,
                entity.stockCode,
                entity.systemScore == null ? null : entity.systemScore.intValue(),
                entity.advice,
                entity.generatedAt,
                entity.technicalAnalysis,
                entity.riskWarning,
                entity.buySellPoints,
                entity.conditionalStrategy,
                tradePlanReviews == null ? List.of() : List.copyOf(tradePlanReviews),
                entity.promptSummary,
                entity.sourceModel,
                entity.status == null ? null : entity.status.name(),
                entity.errorMessage,
                entity.sampleId,
                entity.strategyReleaseId,
                entity.pipelineRunId,
                entity.lineageStatus,
                entity.lineageIssueJson,
                entity.reportVersion,
                entity.supersedesReportId,
                entity.dataQualityScore,
                entity.calibratedConfidence,
                entity.finalAction,
                dailyDecision
        );
    }

    public record DailyDecision(
            Long decisionItemId,
            LocalDate tradeDate,
            String finalAction,
            String category,
            BigDecimal systemScore,
            BigDecimal riskScore,
            String riskLevel,
            String decisionSource,
            String decisionPolicyVersion,
            String reasonSummary,
            String freshnessStatus,
            BigDecimal dataQualityScore,
            Integer outOfSampleCount,
            String confidenceLevel,
            String unavailableReason
    ) {
    }
}
