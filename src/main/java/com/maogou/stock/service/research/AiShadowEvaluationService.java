package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiDriftEvent;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiShadowEvaluation;
import com.maogou.stock.domain.entity.research.AiShadowEvaluationItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiShadowEvaluationService {

    EvaluationResult evaluate(EvaluationRequest request);

    record PredictionPair(
            AiPrediction champion,
            AiPrediction challenger,
            AiSampleLabel label
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
