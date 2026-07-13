package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiDriftEvent;
import com.maogou.stock.domain.entity.v2.AiFactorPerformanceV2;
import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiFactorPerformanceService {

    EvaluationResult evaluateAndStore(PerformanceBatch batch);

    record Observation(AiSampleV2 sample, AiFactorValueV2 factor, AiLabelV2 label) {
    }

    record DriftThresholds(
            BigDecimal psiWarning,
            BigDecimal psiCritical,
            BigDecimal hitRateDropWarning,
            BigDecimal hitRateDropCritical
    ) {
    }

    record PerformanceBatch(
            Long userId,
            String factorVersion,
            Integer horizonDays,
            String marketRegime,
            String windowType,
            LocalDate windowStartDate,
            LocalDate windowEndDate,
            List<Observation> observations,
            List<Observation> baselineObservations,
            String detectorVersion,
            DriftThresholds thresholds,
            LocalDateTime evaluatedAt
    ) {
    }

    record EvaluationResult(
            List<AiFactorPerformanceV2> performances,
            List<AiDriftEvent> driftEvents,
            List<String> reweightEligibleFactorCodes
    ) {
    }
}
