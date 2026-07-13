package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;

import java.time.LocalDateTime;
import java.util.List;

public interface AiPredictionEngineV2 {

    List<AiPredictionV2> predictAndStore(PredictionBatch batch);

    record PredictionInput(AiSampleV2 sample, List<AiFactorValueV2> factors) {
    }

    record PredictionBatch(
            List<PredictionInput> inputs,
            Long strategyReleaseId,
            Long modelVersionId,
            int horizonDays,
            int topK,
            String inferenceMode,
            LocalDateTime predictedAt
    ) {
    }
}
