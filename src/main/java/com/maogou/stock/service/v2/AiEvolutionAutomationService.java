package com.maogou.stock.service.v2;

import java.time.LocalDateTime;

public interface AiEvolutionAutomationService {

    void runWeeklyForEnabledUsers();

    void runMonthlyForEnabledUsers();

    CycleResult runWeeklyForUser(Long userId, LocalDateTime triggeredAt);

    CycleResult runMonthlyForUser(Long userId, LocalDateTime triggeredAt);

    record CycleResult(
            String status,
            int processedCount,
            int successCount,
            int failedCount,
            String message
    ) {
        public boolean successful() {
            return "SUCCESS".equals(status) || "PARTIAL_SUCCESS".equals(status)
                    || "SKIPPED".equals(status);
        }
    }
}
