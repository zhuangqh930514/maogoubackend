package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiHistoricalBackfillShard;

import java.time.LocalDateTime;

/**
 * Executes exactly one historical backfill shard.
 *
 * <p>The coordinator owns leases and state transitions. This boundary owns
 * business work for one shard and reports immutable progress back through the
 * checkpoint writer. Implementations must not update other shards.</p>
 */
public interface HistoricalBackfillShardExecutor {

    ExecutionResult execute(ExecutionCommand command);

    record ExecutionCommand(
            Long runId,
            AiHistoricalBackfillShard shard,
            AiHistoricalEvidenceImportService.ColdStartPlan plan,
            Long strategyReleaseId,
            Long modelVersionId,
            String featureVersion,
            String factorVersion,
            String labelVersion,
            String calendarVersion,
            String idempotencyKey,
            LocalDateTime requestedAt,
            int attemptNo,
            LeaseGuard leaseGuard,
            CheckpointWriter checkpointWriter
    ) {
        public ExecutionCommand {
            leaseGuard = leaseGuard == null ? () -> { } : leaseGuard;
            checkpointWriter = checkpointWriter == null ? (checkpoint, output, rejected) -> { } : checkpointWriter;
        }

        public ExecutionCommand(
                Long runId,
                AiHistoricalBackfillShard shard,
                AiHistoricalEvidenceImportService.ColdStartPlan plan,
                Long strategyReleaseId,
                Long modelVersionId,
                String idempotencyKey,
                LocalDateTime requestedAt,
                int attemptNo,
                LeaseGuard leaseGuard,
                CheckpointWriter checkpointWriter
        ) {
            this(runId, shard, plan, strategyReleaseId, modelVersionId,
                    "POINT_IN_TIME/1.1.0", "FACTOR/1.1.0", "LABEL/1.1.0", "CALENDAR/1.0.0",
                    idempotencyKey, requestedAt, attemptNo, leaseGuard, checkpointWriter);
        }
    }

    @FunctionalInterface
    interface LeaseGuard {
        void checkpoint();
    }

    @FunctionalInterface
    interface CheckpointWriter {
        void write(String checkpointJson, int outputCount, int rejectedCount);
    }

    record ExecutionResult(
            String status,
            int processedCount,
            int successCount,
            int failedCount,
            int rejectedCount,
            String checkpointJson,
            String outputFingerprint,
            String providerCode,
            String endpointType,
            LocalDateTime nextRetryAt,
            String errorCode,
            String errorMessage,
            String errorDetail
    ) {
        public ExecutionResult {
            status = status == null || status.isBlank() ? "SUCCESS" : status;
        }
    }
}
