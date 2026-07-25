package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.AiTradeRuleConfig;
import com.maogou.stock.domain.entity.research.AiConditionalRuleExperiment;
import com.maogou.stock.domain.entity.research.AiConditionalRuleGovernanceEvent;
import com.maogou.stock.domain.entity.research.AiConditionalRuleShadowObservation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Candidate-only governance for conditional rules. This service never mutates a
 * formal rule configuration until an operator explicitly approves a qualified
 * out-of-sample shadow observation.
 */
public interface AiConditionalRuleGovernanceService {

    CandidateResult createCandidate(Long actorUserId, CandidateRequest request);

    ExperimentResult runWalkForward(Long actorUserId, ExperimentRequest request);

    ShadowResult runShadow(Long actorUserId, ShadowRequest request);

    ApprovalResult approve(Long actorUserId, ApprovalRequest request);

    ApprovalResult reject(Long actorUserId, RejectionRequest request);

    record CandidateRequest(
            Long sourceTradeRuleConfigId,
            String versionNo,
            String name,
            String overrideJson,
            LocalDateTime requestedAt
    ) {
    }

    record ExperimentRequest(
            Long candidateTradeRuleConfigId,
            Integer horizonDays,
            Integer initialTrainDays,
            Integer validationDays,
            Integer testDays,
            Integer stepDays,
            Integer foldCount,
            Integer purgeDays,
            Integer embargoDays,
            String idempotencyKey,
            LocalDateTime evaluatedAt
    ) {
    }

    record ShadowRequest(
            Long experimentId,
            LocalDate windowStartDate,
            LocalDate windowEndDate,
            String idempotencyKey,
            LocalDateTime observedAt
    ) {
    }

    record ApprovalRequest(
            Long shadowObservationId,
            String reason,
            String policyVersion,
            LocalDateTime approvedAt
    ) {
    }

    record RejectionRequest(
            Long shadowObservationId,
            String reason,
            String policyVersion,
            LocalDateTime rejectedAt
    ) {
    }

    record CandidateResult(AiTradeRuleConfig candidate, AiConditionalRuleGovernanceEvent event) {
    }

    record ExperimentResult(
            AiConditionalRuleExperiment experiment,
            List<Map<String, Object>> folds,
            AiConditionalRuleGovernanceEvent event
    ) {
    }

    record ShadowResult(
            AiConditionalRuleShadowObservation observation,
            AiConditionalRuleGovernanceEvent event
    ) {
    }

    record ApprovalResult(
            AiTradeRuleConfig activeConfig,
            AiConditionalRuleGovernanceEvent event
    ) {
    }

    record ExperimentThresholds(
            int minimumEligibleSamples,
            int minimumTriggeredSamples,
            int minimumFolds,
            BigDecimal minimumCoverageRate,
            BigDecimal minimumWilsonLowerBound,
            BigDecimal minimumAverageNetReturn,
            BigDecimal maximumDrawdown
    ) {
    }

    record ShadowThresholds(
            int minimumEligibleSamples,
            int minimumCandidateTriggers,
            int minimumTradingDays,
            BigDecimal minimumCoverageRate,
            BigDecimal minimumReturnAdvantage,
            BigDecimal maximumCandidateDrawdown,
            BigDecimal minimumWilsonLowerBound
    ) {
    }
}
