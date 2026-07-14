package com.maogou.stock.dto.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record AiConditionalStrategyPayload(
        String schemaVersion,
        LocalDate tradeDate,
        LocalDateTime dataAsOf,
        ResearchLineage lineage,
        PositionContext position,
        MarketContext market,
        List<HorizonPlan> tradingPlans,
        List<SignalModel> buyModels,
        List<SignalModel> sellModels,
        RiskScore riskScore,
        RuleConfiguration ruleConfiguration,
        List<String> dataLimitations
) {
    public record ResearchLineage(
            Long sampleId,
            Long predictionId,
            Long strategyReleaseId,
            String strategyReleaseVersion,
            String factorVersion,
            String ruleConfigVersion,
            String configFingerprint,
            BigDecimal dataQualityScore,
            BigDecimal strategyValidationScore
    ) {
    }

    public record PositionContext(
            boolean holding,
            Integer quantity,
            BigDecimal averageCost,
            BigDecimal currentPrice,
            BigDecimal profitRate
    ) {
    }

    public record MarketContext(
            String marketRegime,
            BigDecimal marketChangePct,
            String sectorName,
            BigDecimal sectorChangePct,
            String fundFlowStatus,
            BigDecimal fundFlowValue,
            String dataStatus
    ) {
    }

    public record HorizonPlan(
            Integer horizonDays,
            String title,
            String objective,
            String currentState,
            String currentAction,
            List<ConditionalRule> rules
    ) {
    }

    public record ConditionalRule(
            String ruleCode,
            String state,
            String ifThen,
            List<Condition> triggerConditions,
            boolean matched,
            String action,
            String position,
            String riskWarning,
            BigDecimal signalStrength,
            List<FactorEvidence> factorEvidence
    ) {
    }

    public record SignalModel(
            String modelCode,
            String type,
            String ifThen,
            List<Condition> triggerConditions,
            boolean triggered,
            String referencePrice,
            BigDecimal confidence,
            String position,
            String action,
            String riskWarning,
            List<FactorEvidence> factorEvidence
    ) {
    }

    public record Condition(
            String code,
            String label,
            String metric,
            String operator,
            String threshold,
            String actual,
            Boolean satisfied,
            String dataSource
    ) {
    }

    public record FactorEvidence(
            String factorCode,
            String factorName,
            String factorGroup,
            Boolean hit,
            BigDecimal rawValue,
            BigDecimal normalizedValue,
            BigDecimal learnedWeight,
            BigDecimal historicalSuccessRate,
            Integer sampleCount,
            String confidenceLevel,
            String evidence,
            String source
    ) {
    }

    public record RiskScore(
            BigDecimal total,
            String level,
            List<RiskComponent> components,
            String advice
    ) {
    }

    public record RiskComponent(
            String code,
            String name,
            BigDecimal score,
            BigDecimal weight,
            String evidence,
            String dataStatus
    ) {
    }

    public record RuleConfiguration(
            String version,
            Map<String, BigDecimal> thresholds,
            Map<String, BigDecimal> riskWeights,
            Map<String, String> positions,
            Map<String, Integer> minimumConditions,
            Map<String, List<String>> factorMappings
    ) {
    }

    public record ReviewResult(
            Long id,
            Long reportId,
            Integer horizonDays,
            LocalDate targetTradeDate,
            LocalDate outcomeTradeDate,
            String status,
            String triggeredRuleCode,
            String triggeredState,
            String suggestedAction,
            BigDecimal triggerPrice,
            BigDecimal outcomePrice,
            BigDecimal postTriggerReturn,
            BigDecimal maxFavorableReturn,
            BigDecimal maxAdverseReturn,
            Boolean actionEffective,
            BigDecimal reviewScore,
            String feedbackSummary,
            LocalDateTime evaluatedAt
    ) {
    }
}
