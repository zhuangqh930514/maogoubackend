package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiGlobalDailyResearchExecutor {

    StepOutcome execute(String stepKey, PipelineContext context);

    StepOutcome buildResearchDailyReport(PipelineContext context, String pipelineStatus, String pipelineMessage);

    StepOutcome buildDailyInsight(PipelineContext context, String pipelineStatus, String pipelineMessage);

    default void onPipelineFailure(
            PipelineContext context,
            String failedStepKey,
            String errorMessage
    ) {
    }

    record PipelineContext(
            Long pipelineRunId,
            Long userId,
            LocalDate tradeDate,
            Long dataBatchId,
            Long strategyReleaseId,
            Long modelVersionId,
            String idempotencyKey,
            String inputFingerprint,
            LocalDateTime startedAt,
            LeaseGuard leaseGuard
    ) {
        public PipelineContext(
                Long pipelineRunId,
                Long userId,
                LocalDate tradeDate,
                Long dataBatchId,
                Long strategyReleaseId,
                Long modelVersionId,
                String idempotencyKey,
                String inputFingerprint,
                LocalDateTime startedAt
        ) {
            this(pipelineRunId, userId, tradeDate, dataBatchId, strategyReleaseId, modelVersionId,
                    idempotencyKey, inputFingerprint, startedAt, LeaseGuard.NOOP);
        }

        public PipelineContext {
            leaseGuard = leaseGuard == null ? LeaseGuard.NOOP : leaseGuard;
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
            int processedCount,
            int successCount,
            int failedCount,
            String checkpointJson,
            String outputFingerprint,
            List<String> errors
    ) {
        public StepOutcome {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
