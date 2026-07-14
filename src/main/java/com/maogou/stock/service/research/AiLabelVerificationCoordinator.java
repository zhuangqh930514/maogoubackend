package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiLabelVerificationCoordinator {

    VerificationResult verifyMatured(Long userId, LocalDate tradeDate, LocalDateTime verifiedAt);

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
