package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiHistoricalBootstrapService {

    BootstrapResult run(BootstrapRequest request);

    record BootstrapRequest(
            LocalDate startDate,
            LocalDate endDate,
            Long strategyReleaseId,
            Long modelVersionId,
            String idempotencyKey,
            LocalDateTime requestedAt,
            AiHistoricalEvidenceImportService.ColdStartPlan coldStartPlan
    ) {
        public BootstrapRequest(
                LocalDate startDate,
                LocalDate endDate,
                Long strategyReleaseId,
                Long modelVersionId,
                String idempotencyKey,
                LocalDateTime requestedAt
        ) {
            this(startDate, endDate, strategyReleaseId, modelVersionId,
                    idempotencyKey, requestedAt, null);
        }
    }

    record BootstrapResult(
            String status,
            AiPipelineRun run,
            List<AiPipelineStep> checkpoints,
            int processedTradingDays,
            List<String> errors
    ) {
        public BootstrapResult {
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
