package com.maogou.stock.dto.ai;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AiStrategyEvolutionResponse(
        boolean schemaReady,
        String message,
        List<StrategyVersion> versions,
        List<EvolutionLog> logs
) {
    public record StrategyVersion(
            Long id,
            String versionNo,
            String title,
            String status,
            LocalDateTime createdAt,
            BigDecimal successRate,
            BigDecimal avgReturn,
            BigDecimal maxDrawdown,
            Integer sampleCount,
            String strategySummary,
            String factorSnapshot,
            String promptTemplate
    ) {
    }

    public record EvolutionLog(
            Long id,
            Long strategyVersionId,
            String actionType,
            String actionSummary,
            String beforeSnapshot,
            String afterSnapshot,
            LocalDateTime createdAt
    ) {
    }
}
