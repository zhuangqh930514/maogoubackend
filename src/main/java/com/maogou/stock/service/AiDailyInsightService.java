package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiDailyInsightPayloads;

public interface AiDailyInsightService {
    AiDailyInsightPayloads.DailyInsightResponse today();

    AiDailyInsightPayloads.DailyInsightResponse rebuildToday();

    AiDailyInsightPayloads.DailyInsightResponse rebuildForCurrentUser(String pipelineStatus, String pipelineMessage);
}
