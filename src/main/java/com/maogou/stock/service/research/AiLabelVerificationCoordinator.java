package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiLabelVerificationCoordinator {

    VerificationResult matureSampleLabels(LocalDate tradeDate, LocalDateTime verifiedAt);

    VerificationResult matureSampleLabels(
            LocalDate tradeDate,
            LocalDateTime verifiedAt,
            int candidateLimit
    );

    /** Historical replay must keep labels inside the originating backfill run. */
    VerificationResult matureSampleLabels(
            LocalDate tradeDate,
            LocalDateTime verifiedAt,
            int candidateLimit,
            Long historicalBackfillRunId
    );

    VerificationResult evaluatePredictions(LocalDate tradeDate, LocalDateTime evaluatedAt);

    VerificationResult evaluatePredictions(
            LocalDate tradeDate,
            LocalDateTime evaluatedAt,
            int candidateLimit
    );

    /** Processes only recently due T+1/T+2/T+3 predictions for the daily SLA. */
    default VerificationResult evaluateDueDailyPredictions(
            LocalDate tradeDate,
            LocalDateTime evaluatedAt,
            int candidateLimit
    ) {
        return evaluatePredictions(tradeDate, evaluatedAt, candidateLimit);
    }

    VerificationResult evaluateDueDailyPredictions(
            LocalDate tradeDate,
            LocalDateTime evaluatedAt,
            int candidateLimit,
            Long historicalBackfillRunId
    );

    /** Historical work is intentionally separate from the daily due lane. */
    default VerificationResult evaluateHistoricalBacklog(
            LocalDate tradeDate,
            LocalDateTime evaluatedAt,
            int candidateLimit
    ) {
        return evaluatePredictions(tradeDate, evaluatedAt, candidateLimit);
    }

    VerificationResult evaluateHistoricalBacklog(
            LocalDate tradeDate,
            LocalDateTime evaluatedAt,
            int candidateLimit,
            Long historicalBackfillRunId
    );

    record VerificationResult(
            int processedCount,
            int successCount,
            int failedCount,
            List<String> errors,
            List<String> warnings,
            String outputFingerprint
    ) {
        public VerificationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public VerificationResult(
                int processedCount,
                int successCount,
                int failedCount,
                List<String> errors,
                String outputFingerprint
        ) {
            this(processedCount, successCount, failedCount, errors, List.of(), outputFingerprint);
        }
    }
}
