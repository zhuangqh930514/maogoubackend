package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.util.List;

/**
 * Builds the small, high-priority set of user-facing reports that a daily decision can safely use.
 */
public interface AiDailyReportGenerationService {

    GenerationResult generate(GenerationRequest request);

    record GenerationRequest(
            Long userId,
            LocalDate tradeDate,
            Long dataBatchId,
            Long strategyReleaseId
    ) {
    }

    record GenerationResult(
            int eligibleCount,
            int selectedCount,
            int generatedCount,
            int reusedCount,
            List<StockIssue> skipped,
            List<StockIssue> failed
    ) {
        public GenerationResult {
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }

        public int processedCount() {
            return selectedCount + skipped.size();
        }

        public int successCount() {
            return generatedCount + reusedCount;
        }

        public int failedCount() {
            return failed.size();
        }

        public int retryableFailureCount() {
            return (int) failed.stream().filter(issue -> retryable(issue.reason())).count();
        }

        public String summary() {
            return "报告候选=" + selectedCount
                    + "，新生成=" + generatedCount
                    + "，复用=" + reusedCount
                    + "，跳过=" + skipped.size()
                    + "，失败=" + failed.size();
        }

        private static boolean retryable(String reason) {
            if (reason == null || reason.isBlank()) {
                return false;
            }
            String value = reason.toLowerCase(java.util.Locale.ROOT);
            return value.contains("限流") || value.contains("队列繁忙")
                    || value.contains("超时") || value.contains("网络")
                    || value.contains("temporarily") || value.contains("timeout")
                    || value.contains("connection") || value.contains("503") || value.contains("502");
        }
    }

    record StockIssue(String stockCode, String reason) {
    }
}
