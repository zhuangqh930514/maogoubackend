package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AiGlobalResearchPreparationService {

    PreparedPipeline prepare(Long userId, LocalDate tradeDate, LocalDateTime startedAt, String idempotencyKey);

    record PreparedPipeline(
            Long dataBatchId,
            Long strategyReleaseId,
            Long modelVersionId,
            String inputFingerprint
    ) {
    }
}
