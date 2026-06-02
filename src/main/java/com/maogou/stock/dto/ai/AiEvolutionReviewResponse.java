package com.maogou.stock.dto.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AiEvolutionReviewResponse(
        boolean schemaReady,
        String message,
        Integer reportCount,
        Integer verifiedCount,
        Integer pendingCount,
        List<ReviewItem> items
) {
    public record ReviewItem(
            Long id,
            Long reportId,
            String stockCode,
            String stockName,
            LocalDate reportDate,
            Integer horizonDays,
            String predictionDirection,
            String actualDirection,
            BigDecimal entryPrice,
            BigDecimal closePrice,
            BigDecimal pctChange,
            BigDecimal maxDrawdown,
            Boolean directionCorrect,
            Boolean success,
            BigDecimal successScore,
            LocalDateTime evaluatedAt
    ) {
    }
}
