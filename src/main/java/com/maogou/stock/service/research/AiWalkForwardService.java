package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiWalkForwardBaseline;
import com.maogou.stock.domain.entity.research.AiWalkForwardFold;
import com.maogou.stock.domain.entity.research.AiWalkForwardRun;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiWalkForwardService {

    WalkForwardResult runAndStore(WalkForwardRequest request);

    record Observation(
            Long sampleId,
            LocalDate tradeDate,
            LocalDate labelAvailableDate,
            String stockCode,
            BigDecimal realizedNetReturn,
            BigDecimal strategyScore,
            BigDecimal momentumScore,
            BigDecimal championScore,
            String lineageFingerprint
    ) {
    }

    record BenchmarkPoint(
            LocalDate tradeDate,
            BigDecimal dailyReturn,
            String sourceFingerprint
    ) {
    }

    record WalkForwardConfig(
            Integer initialTrainDays,
            Integer validationDays,
            Integer testDays,
            Integer stepDays,
            Integer topK,
            Integer bootstrapIterations
    ) {
    }

    record WalkForwardRequest(
            Long trainingDatasetId,
            Long strategyReleaseId,
            Long modelVersionId,
            String runKey,
            String engineVersion,
            String objective,
            Integer horizonDays,
            Integer purgeDays,
            Integer embargoDays,
            Integer foldCount,
            Long randomSeed,
            WalkForwardConfig config,
            List<Observation> observations,
            String benchmarkCode,
            List<BenchmarkPoint> benchmark,
            LocalDateTime evaluatedAt
    ) {
    }

    record FoldExecution(
            Integer foldNo,
            List<Long> trainSampleIds,
            List<Long> validationSampleIds,
            List<Long> testSampleIds
    ) {
    }

    record WalkForwardResult(
            AiWalkForwardRun run,
            List<AiWalkForwardFold> folds,
            List<AiWalkForwardBaseline> baselines,
            List<FoldExecution> executions
    ) {
    }
}
