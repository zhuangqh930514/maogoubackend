package com.maogou.stock.service;

import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.service.impl.research.DecisionPolicyShadow;

import java.time.LocalDate;
import java.util.Map;

public interface AiDecisionPolicyShadowService {
    void record(
            Long userId,
            LocalDate tradeDate,
            AiSample sample,
            Map<Integer, AiPrediction> predictions,
            DecisionPolicyShadow.Input input,
            AiDailyDecisionItem activeItem
    );
}
