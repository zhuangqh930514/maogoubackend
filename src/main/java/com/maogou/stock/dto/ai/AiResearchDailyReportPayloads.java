package com.maogou.stock.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.maogou.stock.domain.entity.v2.AiResearchDailyReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AiResearchDailyReportPayloads {

    private AiResearchDailyReportPayloads() {
    }

    public record ReportView(
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
            BigDecimal dataQualityScore,
            ReportContent content,
            String markdownContent,
            LocalDateTime generatedAt
    ) {
        public static ReportView from(AiResearchDailyReport entity, ReportContent content) {
            return new ReportView(
                    entity.id,
                    entity.tradeDate,
                    entity.reportVersion,
                    entity.pipelineRunId,
                    entity.strategyReleaseId,
                    entity.modelVersionId,
                    entity.supersedesReportId,
                    entity.isCurrent != null && entity.isCurrent == 1,
                    entity.reportStatus,
                    entity.title,
                    entity.executiveSummary,
                    entity.marketRegime,
                    entity.recommendationCount,
                    entity.watchCount,
                    entity.avoidCount,
                    entity.holdingRiskCount,
                    entity.freshnessStatus,
                    entity.dataQualityScore,
                    content,
                    entity.markdownContent,
                    entity.generatedAt
            );
        }
    }

    public record ReportListItem(
            Long id,
            LocalDate tradeDate,
            Integer reportVersion,
            String reportStatus,
            String title,
            String freshnessStatus,
            BigDecimal dataQualityScore,
            boolean current,
            LocalDateTime generatedAt
    ) {
        public static ReportListItem from(AiResearchDailyReport entity) {
            return new ReportListItem(
                    entity.id,
                    entity.tradeDate,
                    entity.reportVersion,
                    entity.reportStatus,
                    entity.title,
                    entity.freshnessStatus,
                    entity.dataQualityScore,
                    entity.isCurrent != null && entity.isCurrent == 1,
                    entity.generatedAt
            );
        }
    }

    public record ReportContent(
            Freshness freshness,
            PipelineSummary pipeline,
            StrategyPerformance strategyPerformance,
            List<StockCard> recommendations,
            List<StockCard> watches,
            List<StockCard> avoids,
            List<StockCard> holdingRisks,
            List<FactorCard> keyFactors
    ) {
    }

    public record StockCard(
            String stockCode,
            String stockName,
            String action,
            String actionBucket,
            BigDecimal compositeScore,
            BigDecimal riskScore,
            BigDecimal historicalHitRate,
            Integer historicalSampleCount,
            String confidenceLevel,
            String freshnessStatus,
            String reasonSummary,
            Long reportId,
            Long predictionId,
            Long sampleId
    ) {
    }

    public record FactorCard(
            String factorCode,
            String factorName,
            String direction,
            BigDecimal contribution,
            String evidence
    ) {
    }

    public record Freshness(
            String status,
            BigDecimal dataQualityScore,
            LocalDateTime latestSampleAt,
            LocalDateTime latestReportAt,
            LocalDateTime generatedAt
    ) {
    }

    public record StrategyPerformance(
            Long strategyReleaseId,
            String versionNo,
            String title,
            Long modelVersionId,
            BigDecimal totalReturn,
            BigDecimal alpha,
            BigDecimal maxDrawdown,
            BigDecimal sharpeRatio,
            Integer sampleCount,
            BigDecimal hitRate,
            String driftStatus
    ) {
    }

    public record PipelineSummary(
            Long pipelineRunId,
            String status,
            String currentStep,
            String failedStep,
            Integer processedCount,
            Integer successCount,
            Integer failedCount,
            String errorMessage,
            List<PipelineStep> steps
    ) {
    }

    public record PipelineStep(
            String stepKey,
            String status,
            Integer inputCount,
            Integer outputCount,
            String errorMessage
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TriggerFactor(
            String factorCode,
            String factorName,
            String direction,
            BigDecimal contribution,
            String evidence
    ) {
    }
}
