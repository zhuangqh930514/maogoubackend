package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiStrategyGovernanceEvent;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AiStrategyGovernanceService {

    Assessment assess(AssessmentRequest request);

    PromotionResult confirmPromotion(ConfirmationRequest request);

    RollbackResult rollback(RollbackRequest request);

    record PromotionPolicy(
            Integer minimumShadowDays,
            Integer minimumSamples,
            Integer minimumTrades,
            Integer minimumFolds,
            BigDecimal maximumDrawdown,
            BigDecimal maximumSingleStockContribution,
            BigDecimal minimumConfidenceIntervalExcessReturn
    ) {
    }

    record PromotionEvidence(
            Integer shadowTradingDays,
            Integer sampleCount,
            Integer tradeCount,
            Integer foldCount,
            BigDecimal challengerExcessReturn,
            BigDecimal maxDrawdown,
            BigDecimal maxSingleStockContribution,
            BigDecimal confidenceIntervalLowerExcessReturn,
            Integer criticalDriftCount,
            String evidenceFingerprint
    ) {
    }

    record AssessmentRequest(
            Long userId,
            Long challengerReleaseId,
            Long currentChampionReleaseId,
            Long walkForwardRunId,
            Long backtestRunId,
            Long shadowEvaluationId,
            String policyVersion,
            PromotionPolicy policy,
            PromotionEvidence evidence,
            LocalDateTime assessedAt
    ) {
    }

    record Assessment(
            String decisionStatus,
            List<String> reasons,
            AiStrategyRelease challenger,
            AiStrategyGovernanceEvent event
    ) {
    }

    record ConfirmationRequest(
            Long userId,
            Long challengerReleaseId,
            String assessmentEventKey,
            Long actorId,
            String policyVersion,
            String confirmationReason,
            LocalDateTime confirmedAt
    ) {
    }

    record PromotionResult(
            AiStrategyRelease champion,
            AiStrategyRelease previousChampion,
            AiStrategyGovernanceEvent event
    ) {
    }

    record RollbackRequest(
            Long userId,
            Long currentChampionReleaseId,
            Long previousChampionReleaseId,
            Long shadowEvaluationId,
            Integer criticalDriftCount,
            String degradationFingerprint,
            String policyVersion,
            String rollbackReason,
            LocalDateTime rolledBackAt
    ) {
    }

    record RollbackResult(
            AiStrategyRelease restoredChampion,
            AiStrategyRelease retiredChampion,
            AiStrategyGovernanceEvent event
    ) {
    }
}
