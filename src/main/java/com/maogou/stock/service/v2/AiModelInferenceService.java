package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;

import java.util.List;

public interface AiModelInferenceService {

    ModelInference infer(
            Long userId,
            Long modelVersionId,
            AiSampleV2 sample,
            List<AiFactorValueV2> factors
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
