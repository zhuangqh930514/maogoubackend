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

    VerificationResult evaluatePredictions(LocalDate tradeDate, LocalDateTime evaluatedAt);

    VerificationResult evaluatePredictions(
            LocalDate tradeDate,
            LocalDateTime evaluatedAt,
            int candidateLimit
    );

    record VerificationResult(
            int processedCount,
            int successCount,
            int failedCount,
            List<String> errors,
            String outputFingerprint
    ) {
        public VerificationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
