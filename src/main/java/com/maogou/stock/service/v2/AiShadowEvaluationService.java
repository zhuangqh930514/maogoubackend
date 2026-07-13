package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiDriftEvent;
import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiShadowEvaluation;
import com.maogou.stock.domain.entity.v2.AiShadowEvaluationItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiShadowEvaluationService {

    EvaluationResult evaluate(EvaluationRequest request);

    record PredictionPair(
            AiPredictionV2 champion,
            AiPredictionV2 challenger,
            AiLabelV2 label
    ) {
    }

    record ShadowThresholds(
            BigDecimal minimumCoverageRate,
            BigDecimal minimumDirectionAgreementRate,
            BigDecimal minimumExcessReturnAdvantage,
            BigDecimal maximumDrawdown,
            BigDecimal featureDriftWarning,
            BigDecimal featureDriftCritical,
            String detectorVersion
    ) {
    }

    record GovernanceContext(
            Long walkForwardRunId,
            Long backtestRunId,
            AiStrategyGovernanceService.PromotionPolicy policy,
            Integer shadowTradingDays,
            Integer tradeCount,
            Integer foldCount,
            BigDecimal maxSingleStockContribution,
            BigDecimal confidenceIntervalLowerExcessReturn,
            String policyVersion
    ) {
    }

    record EvaluationRequest(
            Long userId,
            Long pipelineRunId,
            Long trainingDatasetId,
            Long championReleaseId,
            Long challengerReleaseId,
            LocalDate windowStartDate,
            LocalDate windowEndDate,
            String evaluationVersion,
            Integer windowSampleCount,
            List<PredictionPair> pairs,
            BigDecimal featureDriftScore,
            ShadowThresholds thresholds,
            GovernanceContext governance,
            LocalDateTime evaluatedAt
    ) {
        public EvaluationRequest {
            if (pairs != null) {
                pairs = List.copyOf(pairs);
            }
        }
    }

    record EvaluationResult(
            AiShadowEvaluation evaluation,
            List<AiShadowEvaluationItem> items,
            List<AiDriftEvent> driftEvents,
            AiStrategyGovernanceService.Assessment governanceAssessment
    ) {
    }
}
