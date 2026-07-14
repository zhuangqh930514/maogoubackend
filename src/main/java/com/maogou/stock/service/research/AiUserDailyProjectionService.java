package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItemPrediction;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiUserDailyProjectionService {

    ProjectionResult project(ProjectionRequest request);

    AiDailyDecisionSnapshot current(Long userId, LocalDate tradeDate);

    record ProjectionRequest(
            Long userId,
            LocalDate tradeDate,
            Long globalPipelineRunId,
            Long userPipelineRunId,
            String idempotencyKey,
            LocalDateTime generatedAt
    ) {
    }

    record ProjectionResult(
            AiDailyDecisionSnapshot snapshot,
            List<AiDailyDecisionItem> items,
            List<AiDailyDecisionItemPrediction> predictionLinks,
            List<String> completedSteps
    ) {
        public ProjectionResult {
            items = List.copyOf(items);
            predictionLinks = List.copyOf(predictionLinks);
            completedSteps = List.copyOf(completedSteps);
        }
    }
}
