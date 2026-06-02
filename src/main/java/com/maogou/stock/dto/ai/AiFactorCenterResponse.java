package com.maogou.stock.dto.ai;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AiFactorCenterResponse(
        boolean schemaReady,
        String message,
        Integer factorCount,
        Integer sampleCount,
        List<FactorItem> factors
) {
    public record FactorItem(
            Long id,
            String factorCode,
            String factorName,
            String factorGroup,
            String marketRegime,
            Integer sampleCount,
            Integer successCount,
            BigDecimal successRate,
            BigDecimal avgReturn,
            BigDecimal avgDrawdown,
            BigDecimal weightScore,
            LocalDateTime lastEvaluatedAt
    ) {
    }
}
