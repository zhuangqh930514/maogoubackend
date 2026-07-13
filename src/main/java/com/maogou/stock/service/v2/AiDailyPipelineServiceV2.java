package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiPipelineRun;
import com.maogou.stock.domain.entity.v2.AiPipelineStep;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiDailyPipelineServiceV2 {

    PipelineResult run(PipelineRequest request);

    record PipelineRequest(
            Long userId,
            LocalDate tradeDate,
            Long dataBatchId,
            Long strategyReleaseId,
            Long modelVersionId,
            String idempotencyKey,
            String inputFingerprint,
            LocalDateTime startedAt
    ) {
    }

    record PipelineResult(AiPipelineRun run, List<AiPipelineStep> steps) {
        public PipelineResult {
            steps = List.copyOf(steps);
        }
    }
}
