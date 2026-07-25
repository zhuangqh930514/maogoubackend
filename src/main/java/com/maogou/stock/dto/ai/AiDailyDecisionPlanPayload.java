package com.maogou.stock.dto.ai;

import java.time.LocalDate;

/**
 * Stored with a deterministic daily-decision plan. The payload captures the
 * original close-time rule snapshot so a later review never evaluates against
 * an edited rule configuration.
 */
public record AiDailyDecisionPlanPayload(
        String schemaVersion,
        String planSource,
        Long decisionItemId,
        Long sampleId,
        String stockCode,
        LocalDate tradeDate,
        Integer horizonDays,
        String officialAction,
        String decisionReason,
        AiConditionalStrategyPayload conditionalStrategy
) {
    public static final String SCHEMA_VERSION = "DAILY_DECISION_PLAN_V1";
}
