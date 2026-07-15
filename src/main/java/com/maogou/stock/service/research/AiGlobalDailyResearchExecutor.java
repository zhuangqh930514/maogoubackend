package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface AiGlobalDailyResearchExecutor {

    StepOutcome execute(String stepKey, PipelineContext context);

    default void onPipelineFailure(
            PipelineContext context,
            String failedStepKey,
            String errorMessage
    ) {
    }

    record PipelineContext(
            Long pipelineRunId,
            LocalDate tradeDate,
            Long strategyReleaseId,
            Long modelVersionId,
            String idempotencyKey,
            String inputFingerprint,
            LocalDateTime startedAt,
            int attemptNo,
            Map<String, String> checkpoints,
            LeaseGuard leaseGuard
    ) {
        public PipelineContext(
                Long pipelineRunId,
                LocalDate tradeDate,
                Long strategyReleaseId,
                Long modelVersionId,
                String idempotencyKey,
                String inputFingerprint,
                LocalDateTime startedAt
        ) {
            this(pipelineRunId, tradeDate, strategyReleaseId, modelVersionId,
                    idempotencyKey, inputFingerprint, startedAt, 0, Map.of(), LeaseGuard.NOOP);
        }

        public PipelineContext {
            attemptNo = Math.max(0, attemptNo);
            checkpoints = checkpoints == null ? Map.of() : Map.copyOf(checkpoints);
            leaseGuard = leaseGuard == null ? LeaseGuard.NOOP : leaseGuard;
        }

        public String checkpoint(String stepKey) {
            return checkpoints.get(stepKey);
        }

        public void checkpointLease() {
            leaseGuard.checkpoint();
        }
    }

    @FunctionalInterface
    interface LeaseGuard {
        LeaseGuard NOOP = () -> {
        };

        void checkpoint();
    }

    record StepOutcome(
            String status,
            int processedCount,
            int successCount,
            int failedCount,
            String checkpointJson,
            String outputFingerprint,
            List<String> errors,
            Long dataBatchId,
            LocalDateTime nextRetryAt
    ) {
        public StepOutcome {
            status = status == null || status.isBlank() ? "SUCCESS" : status;
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
