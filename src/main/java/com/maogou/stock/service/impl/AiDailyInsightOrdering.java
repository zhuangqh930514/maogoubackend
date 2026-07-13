package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiDailyInsightItem;

import java.math.BigDecimal;
import java.util.Comparator;

final class AiDailyInsightOrdering {

    private AiDailyInsightOrdering() {
    }

    static Comparator<AiDailyInsightItem> comparator() {
        return Comparator.comparingInt((AiDailyInsightItem item) -> bucketRank(item.actionBucket))
                .thenComparing((AiDailyInsightItem item) -> safe(item.compositeScore), Comparator.reverseOrder())
                .thenComparing(item -> safe(item.riskScore));
    }

    private static int bucketRank(String bucket) {
        return "RECOMMEND".equals(bucket) ? 0 : "WATCH".equals(bucket) ? 1 : 2;
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
