package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSampleLabel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AiPredictionEvaluationService {

    List<AiPredictionEvaluation> evaluateAndStore(EvaluationBatch batch);

    record PredictionInput(
            Long predictionId,
            Long sampleId,
            Integer horizonTradingDays,
            String action,
            String actionBucket,
            String targetDirection,
            BigDecimal expectedReturn,
            BigDecimal probabilityUp,
            BigDecimal probabilityDown,
            String inputFingerprint
    ) {
    }

    record EvaluationBatch(
            List<PredictionInput> predictions,
            List<AiSampleLabel> labels,
            String evaluationVersion,
            LocalDateTime evaluatedAt
    ) {
        public EvaluationBatch {
            predictions = predictions == null ? List.of() : List.copyOf(predictions);
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }
}
