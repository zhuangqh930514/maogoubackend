package com.maogou.stock.service.research;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Re-runs the immutable model quality gate for an already imported candidate.
 * A model can only become a shadow challenger through this service or the
 * import path; callers cannot promote a candidate by changing a request flag.
 */
public interface AiImportedModelQualificationService {

    QualificationResult qualifyAndCreateShadow(Long modelId, LocalDateTime now);

    record QualificationResult(
            Long modelId,
            String modelStatus,
            boolean qualityGatePassed,
            List<AiModelQualityGate.Check> checks,
            Long challengerId,
            String challengerStatus,
            String message
    ) {
        public QualificationResult {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }
}
