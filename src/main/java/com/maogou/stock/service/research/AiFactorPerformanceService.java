package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiDriftEvent;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiSample;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiFactorPerformanceService {

    EvaluationResult evaluateAndStore(PerformanceBatch batch);

    record Observation(AiSample sample, AiFactorValue factor, AiSampleLabel label) {
    }

    record DriftThresholds(
            BigDecimal psiWarning,
            BigDecimal psiCritical,
            BigDecimal hitRateDropWarning,
            BigDecimal hitRateDropCritical
    ) {
    }

    record PerformanceBatch(
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
            List<AiFactorPerformance> performances,
            List<AiDriftEvent> driftEvents,
            List<String> reweightEligibleFactorCodes
    ) {
    }
}
