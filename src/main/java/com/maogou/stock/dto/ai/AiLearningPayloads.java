package com.maogou.stock.dto.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AiLearningPayloads {
    private AiLearningPayloads() {
    }

    public record Metric(String label, String value, String helper, String tone) {
    }

    public record CurvePoint(String label, BigDecimal value, BigDecimal benchmarkValue) {
    }

    public record AlertItem(String title, String message, String level) {
    }

    public record LearningDashboardResponse(
            boolean schemaReady,
            String message,
            List<Metric> metrics,
            List<CurvePoint> winRateCurve,
            List<CurvePoint> equityCurve,
            List<FactorPerformanceItem> topFactors,
            List<AlertItem> alerts,
            String activeStrategy
    ) {
    }

    public record SampleItem(
            Long id,
            String stockCode,
            String stockName,
            LocalDateTime sampleTime,
            LocalDate tradeDate,
            String samplePhase,
            String universeCode,
            String marketRegime,
            BigDecimal dataQualityScore,
            boolean tradable,
            String excludeReason,
            Integer factorCount,
            Integer predictionCount,
            Integer labelCount
    ) {
    }

    public record SampleCenterResponse(
            boolean schemaReady,
            String message,
            Integer sampleCount,
            Integer tradableCount,
            BigDecimal avgDataQuality,
            List<SampleItem> samples
    ) {
    }

    public record SampleDetailResponse(
            boolean schemaReady,
            String message,
            SampleItem sample,
            String featureSnapshot,
            List<FactorValueItem> factors,
            List<PredictionItem> predictions,
            List<LabelItem> labels
    ) {
    }

    public record FactorDefinitionItem(
            Long id,
            String factorCode,
            String factorName,
            String factorGroup,
            String direction,
            String formulaDesc,
            BigDecimal defaultWeight,
            boolean enabled,
            String versionNo
    ) {
    }

    public record FactorValueItem(
            Long id,
            Long sampleId,
            String stockCode,
            String factorCode,
            String factorName,
            String factorGroup,
            BigDecimal factorValue,
            BigDecimal normalizedValue,
            boolean hit,
            String direction,
            String evidence,
            LocalDateTime calculatedAt
    ) {
    }

    public record FactorPerformanceItem(
            String factorCode,
            String factorName,
            String factorGroup,
            String marketRegime,
            Integer sampleCount,
            Integer successCount,
            BigDecimal successRate,
            BigDecimal avgReturn,
            BigDecimal avgDrawdown,
            BigDecimal weightScore,
            BigDecimal confidenceLowerBound,
            LocalDateTime lastEvaluatedAt
    ) {
    }

    public record FactorCorrelationItem(
            String leftFactorCode,
            String rightFactorCode,
            BigDecimal coHitRate,
            BigDecimal avgReturn,
            Integer sampleCount
    ) {
    }

    public record FactorFactoryResponse(
            boolean schemaReady,
            String message,
            Integer definitionCount,
            Integer enabledDefinitionCount,
            Integer factorValueCount,
            List<FactorDefinitionItem> definitions,
            List<FactorPerformanceItem> performances,
            List<FactorCorrelationItem> correlations
    ) {
    }

    public record PredictionItem(
            Long id,
            Long sampleId,
            Long reportId,
            String stockCode,
            String stockName,
            String action,
            String targetDirection,
            Integer horizonDays,
            BigDecimal confidence,
            BigDecimal score,
            Integer rankNo,
            BigDecimal riskScore,
            String reasonJson,
            LocalDateTime createdAt
    ) {
    }

    public record PredictionCenterResponse(
            boolean schemaReady,
            String message,
            Integer predictionCount,
            Integer buyCount,
            BigDecimal avgScore,
            List<PredictionItem> predictions
    ) {
    }

    public record PredictionRankResponse(
            boolean schemaReady,
            String message,
            String universeCode,
            Integer horizonDays,
            Integer topK,
            List<PredictionItem> predictions
    ) {
    }

    public record LabelItem(
            Long id,
            Long predictionId,
            Long sampleId,
            String stockCode,
            String stockName,
            Integer horizonDays,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal closeReturn,
            BigDecimal maxFavorableReturn,
            BigDecimal maxAdverseReturn,
            BigDecimal excessReturn,
            BigDecimal netReturn,
            boolean hitDirection,
            boolean hitTarget,
            boolean hitStopLoss,
            boolean tradable,
            BigDecimal labelScore,
            String labelStatus,
            LocalDateTime evaluatedAt
    ) {
    }

    public record LabelCenterResponse(
            boolean schemaReady,
            String message,
            Integer labelCount,
            Integer hitCount,
            BigDecimal hitRate,
            BigDecimal avgNetReturn,
            BigDecimal avgMaxDrawdown,
            List<LabelItem> labels
    ) {
    }

    public record ExperimentItem(
            Long id,
            String title,
            String status,
            String universeCode,
            LocalDate trainStartDate,
            LocalDate trainEndDate,
            LocalDate validationStartDate,
            LocalDate validationEndDate,
            LocalDate testStartDate,
            LocalDate testEndDate,
            String metricsJson,
            String baselineMetricsJson,
            boolean canPromote,
            Long promotedStrategyVersionId,
            LocalDateTime createdAt
    ) {
    }

    public record ExperimentCenterResponse(
            boolean schemaReady,
            String message,
            List<ExperimentItem> experiments
    ) {
    }

    public record BacktestRunItem(
            Long id,
            String title,
            String universeCode,
            Integer horizonDays,
            Integer topK,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal totalReturn,
            BigDecimal winRate,
            BigDecimal avgReturn,
            BigDecimal maxDrawdown,
            BigDecimal benchmarkReturn,
            Integer tradeCount,
            String status,
            LocalDateTime createdAt
    ) {
    }

    public record BacktestTradeItem(
            Long id,
            Long predictionId,
            String stockCode,
            String stockName,
            LocalDate entryDate,
            LocalDate exitDate,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal netReturn,
            BigDecimal maxDrawdown,
            Integer rankNo
    ) {
    }

    public record BacktestCenterResponse(
            boolean schemaReady,
            String message,
            List<BacktestRunItem> runs
    ) {
    }

    public record BacktestDetailResponse(
            boolean schemaReady,
            String message,
            BacktestRunItem run,
            String metricsJson,
            String equityCurveJson,
            List<BacktestTradeItem> trades
    ) {
    }

    public record ModelEvalItem(
            Long id,
            String modelName,
            String provider,
            Long promptTemplateId,
            String evalType,
            BigDecimal jsonSuccessRate,
            BigDecimal avgLatencyMs,
            Integer sampleCount,
            BigDecimal score,
            String metricsJson,
            String status,
            LocalDateTime createdAt
    ) {
    }

    public record ModelEvalCenterResponse(
            boolean schemaReady,
            String message,
            List<ModelEvalItem> runs
    ) {
    }

    public record BuildSamplesRequest(String universeCode, String samplePhase) {
    }

    public record RankUniverseRequest(String universeCode, Integer horizonDays, Integer topK) {
    }

    public record RunExperimentRequest(String title, String universeCode) {
    }

    public record RunBacktestRequest(String title, String universeCode, Integer horizonDays, Integer topK) {
    }

    public record RunModelEvalRequest(String evalType, Integer sampleCount) {
    }

    public record AnalysisLearningContext(
            Long sampleId,
            Long predictionId,
            Long strategyVersionId,
            BigDecimal dataQualityScore,
            BigDecimal calibratedConfidence,
            String promptContext
    ) {
        public static AnalysisLearningContext empty() {
            return new AnalysisLearningContext(null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, "");
        }
    }
}
