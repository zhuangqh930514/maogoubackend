package com.maogou.stock.service;

import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.ai.AiLearningPayloads;
import com.maogou.stock.dto.market.StockDetailResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface AiConditionalTradeStrategyService {

    AiConditionalStrategyPayload build(
            Long userId,
            StockDetailResponse detail,
            LocalDate tradeDate,
            AiLearningPayloads.AnalysisLearningContext learningContext
    );

    void initializeReviews(AiAnalysisReport report, AiConditionalStrategyPayload payload);

    Map<Long, List<AiConditionalStrategyPayload.ReviewResult>> reviewsByReportIds(Long userId, List<Long> reportIds);

    ReviewRunResult verifyMatured(Long userId, LocalDate asOfDate);

    record ReviewRunResult(
            int processedCount,
            int verifiedCount,
            int noTriggerCount,
            int pendingCount,
            int failedCount,
            List<String> errors
    ) {
    }
}
