package com.maogou.stock.dto.settings;

import java.time.LocalDateTime;

public record SchedulerJobLogResponse(
        Long id,
        String jobName,
        String jobType,
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Integer processedCount,
        Integer successCount,
        Integer failedCount,
        String errorMessage
) {
}
