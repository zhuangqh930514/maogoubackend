package com.maogou.stock.service.research;

public record AiResearchCycleResult(
        String status,
        int processedCount,
        int successCount,
        int failedCount,
        String message
) {
    public boolean successful() {
        return "SUCCESS".equals(status) || "PARTIAL_SUCCESS".equals(status)
                || "SKIPPED".equals(status) || "INSUFFICIENT_DATA".equals(status);
    }
}
