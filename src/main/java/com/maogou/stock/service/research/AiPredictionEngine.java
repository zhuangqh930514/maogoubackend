package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;

import java.time.LocalDateTime;
import java.util.List;

public interface AiPredictionEngine {

    List<AiPrediction> predictAndStore(PredictionBatch batch);

    record PredictionInput(AiSample sample, List<AiFactorValue> factors) {
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
