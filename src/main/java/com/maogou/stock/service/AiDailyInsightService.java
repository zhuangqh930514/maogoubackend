package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiDailyInsightPayloads;

import java.time.LocalDate;

public interface AiDailyInsightService {
    AiDailyInsightPayloads.DailyInsightResponse today();

    AiDailyInsightPayloads.DailyInsightResponse rebuildToday();

    AiDailyInsightPayloads.DailyInsightResponse rebuildForCurrentUser(String pipelineStatus, String pipelineMessage);

    AiDailyInsightPayloads.DailyInsightResponse rebuildForPipeline(
            Long userId,
            LocalDate tradeDate,
            Long pipelineRunId,
            String pipelineStatus,
            String pipelineMessage
    );
}
