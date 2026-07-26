package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiLearningCoverageService {
    void recordDueEvaluation(Long pipelineRunId, LocalDate tradeDate,
                             AiLabelVerificationCoordinator.VerificationResult result, LocalDateTime generatedAt);

    record Coverage(int horizonDays, long eligibleCount, long evaluationCount, String status, String errorSummary) {
    }

    List<Coverage> find(Long pipelineRunId, LocalDate tradeDate);
}
