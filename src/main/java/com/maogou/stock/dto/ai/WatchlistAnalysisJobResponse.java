package com.maogou.stock.dto.ai;

import com.maogou.stock.domain.entity.AiWatchlistAnalysisJob;

import java.time.LocalDateTime;
import java.util.Set;

public record WatchlistAnalysisJobResponse(
        Long id,
        Long promptTemplateId,
        String status,
        Integer totalCount,
        Integer completedCount,
        Integer analyzedCount,
        Integer skippedCount,
        Integer failedCount,
        String currentStockCode,
        String currentStockName,
        String message,
        String lastError,
        String issueDetails,
        Integer progressPercent,
        Boolean terminal,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "PARTIAL", "FAILED");

    public static WatchlistAnalysisJobResponse from(AiWatchlistAnalysisJob job) {
        if (job == null) {
            return null;
        }
        int total = number(job.totalCount);
        int completed = number(job.completedCount);
        boolean terminal = TERMINAL_STATUSES.contains(job.status);
        int progress = terminal
                ? 100
                : total <= 0 ? ("RUNNING".equals(job.status) ? 5 : 0)
                : Math.min(99, Math.max(0, completed * 100 / total));
        return new WatchlistAnalysisJobResponse(
                job.id,
                job.promptTemplateId,
                job.status,
                total,
                completed,
                number(job.analyzedCount),
                number(job.skippedCount),
                number(job.failedCount),
                job.currentStockCode,
                job.currentStockName,
                job.message,
                job.lastError,
                job.issueDetails,
                progress,
                terminal,
                job.startedAt,
                job.finishedAt,
                job.createdAt,
                job.updatedAt
        );
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }
}
