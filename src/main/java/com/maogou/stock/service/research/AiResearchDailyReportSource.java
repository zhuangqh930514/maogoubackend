package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.AiDailyInsightSnapshot;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;

import java.time.LocalDate;
import java.util.List;

public interface AiResearchDailyReportSource {

    ReportSource load(Long userId, LocalDate tradeDate, PipelineRequest pipelineRequest);

    record PipelineRequest(
            Long pipelineRunId,
            Long strategyReleaseId,
            Long modelVersionId,
            String pipelineStatus,
            String failedStep,
            String pipelineMessage
    ) {
    }

    record ReportSource(
            AiDailyInsightSnapshot snapshot,
            List<AiDailyInsightItem> items,
            List<TradeRecord> holdings,
            String marketRegime,
            AiResearchDailyReportPayloads.StrategyPerformance strategyPerformance,
            AiResearchDailyReportPayloads.PipelineSummary pipeline
    ) {
    }
}
