package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Keeps deterministic formal decisions reviewable without presenting them as AI reports. */
public interface AiDailyDecisionPlanService {

    PlanBuildResult initializeDeterministicPlans(Long userId, LocalDate tradeDate, List<AiDailyDecisionItem> items);

    PlanReviewRunResult verifyMatured(Long userId, LocalDate asOfDate);

    Map<Long, List<AiResearchDailyReportPayloads.DecisionPlan>> plansByDecisionItemIds(
            Long userId,
            List<Long> decisionItemIds
    );

    List<PriorReviewSummary> priorReviewSummaries(Long userId, LocalDate targetTradeDate);

    record PlanBuildResult(int createdCount, int unavailableCount, int failedCount, List<String> errors) {
        public PlanBuildResult {
            errors = List.copyOf(errors);
        }
    }

    record PlanReviewRunResult(int processedCount, int verifiedCount, int noActionCount,
                               int pendingCount, int failedCount, List<String> errors) {
        public PlanReviewRunResult {
            errors = List.copyOf(errors);
        }
    }

    record PriorReviewSummary(
            int horizonDays,
            int dueCount,
            int triggerCheckedCount,
            int effectiveCount,
            int ineffectiveCount,
            int noTriggerCount,
            int unavailableCount,
            int retryableCount
    ) {
    }
}
