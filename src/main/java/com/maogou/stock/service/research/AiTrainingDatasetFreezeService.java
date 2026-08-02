package com.maogou.stock.service.research;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Audits a READY training dataset and atomically publishes an immutable
 * FROZEN manifest. The scan is deliberately separate from the final state
 * update so large datasets never hold a database transaction open.
 */
public interface AiTrainingDatasetFreezeService {

    FreezeResult freeze(FreezeRequest request);

    record FreezeRequest(
            Long runId,
            Long datasetId,
            Long operatorUserId,
            LocalDateTime requestedAt,
            boolean requireAllHorizons
    ) {
        public FreezeRequest(Long runId, Long datasetId, Long operatorUserId, LocalDateTime requestedAt) {
            this(runId, datasetId, operatorUserId, requestedAt, true);
        }
    }

    record FreezeResult(
            Long runId,
            Long datasetId,
            String datasetKey,
            String versionNo,
            String status,
            int rowCount,
            Map<Integer, Integer> horizonCounts,
            List<String> blockingGaps,
            String manifestJson,
            String freezeChecksum,
            LocalDateTime frozenAt
    ) {
        public FreezeResult {
            horizonCounts = horizonCounts == null ? Map.of() : Map.copyOf(horizonCounts);
            blockingGaps = blockingGaps == null ? List.of() : List.copyOf(blockingGaps);
        }
    }
}
