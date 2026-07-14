package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiSample;

import java.util.List;

public interface AiModelInferenceService {

    ModelInference infer(
            Long userId,
            Long modelVersionId,
            AiSample sample,
            List<AiFactorValue> factors
    );

    record ModelInference(
            Long modelVersionId,
            String modelKey,
            String versionNo,
            String artifactChecksum,
            float rawOutput,
            float probabilityUp,
            int featureCount,
            int matchedFeatureCount
    ) {
    }
}
