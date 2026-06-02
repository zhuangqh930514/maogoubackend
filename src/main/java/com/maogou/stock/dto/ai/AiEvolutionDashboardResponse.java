package com.maogou.stock.dto.ai;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AiEvolutionDashboardResponse(
        boolean schemaReady,
        String message,
        List<Metric> metrics,
        List<RecentActivity> recentActivities,
        List<FactorSnapshot> topFactors,
        String activeStrategy
) {
    public record Metric(
            String label,
            String value,
            String helper,
            String tone
    ) {
    }

    public record RecentActivity(
            String title,
            String description,
            LocalDateTime time,
            String type
    ) {
    }

    public record FactorSnapshot(
            String factorCode,
            String factorName,
            String factorGroup,
            Integer sampleCount,
            BigDecimal successRate,
            BigDecimal avgReturn,
            BigDecimal weightScore
    ) {
    }
}
