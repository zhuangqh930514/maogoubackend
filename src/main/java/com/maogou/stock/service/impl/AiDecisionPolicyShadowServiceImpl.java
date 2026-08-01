package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiDecisionPolicyShadowItem;
import com.maogou.stock.mapper.research.AiDecisionPolicyShadowItemMapper;
import com.maogou.stock.service.AiDecisionPolicyShadowService;
import com.maogou.stock.service.impl.research.DecisionPolicyShadow;
import com.maogou.stock.service.research.DecisionPolicyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AiDecisionPolicyShadowServiceImpl implements AiDecisionPolicyShadowService {
    private final AiDecisionPolicyShadowItemMapper mapper;
    private final DecisionPolicyShadow policy;

    public AiDecisionPolicyShadowServiceImpl(AiDecisionPolicyShadowItemMapper mapper) {
        this(mapper, new DecisionPolicyRegistry());
    }

    @Autowired
    public AiDecisionPolicyShadowServiceImpl(
            AiDecisionPolicyShadowItemMapper mapper,
            DecisionPolicyRegistry registry
    ) {
        this.mapper = mapper;
        this.policy = registry.shadowPolicy();
    }

    @Override
    public void record(Long userId, LocalDate tradeDate, AiSample sample,
                       Map<Integer, AiPrediction> predictions, DecisionPolicyShadow.Input input,
                       AiDailyDecisionItem activeItem) {
        if (userId == null || tradeDate == null || sample == null || sample.id == null
                || activeItem == null || input == null) {
            return;
        }
        DecisionPolicyShadow.Decision shadow = policy.decide(input);
        AiDecisionPolicyShadowItem item = new AiDecisionPolicyShadowItem();
        item.userId = userId;
        item.tradeDate = tradeDate;
        item.sampleId = sample.id;
        item.stockCode = sample.stockCode;
        item.activePolicyVersion = activeItem.decisionPolicyVersion == null
                ? "DECISION/1.1.0" : activeItem.decisionPolicyVersion;
        item.shadowPolicyVersion = DecisionPolicyShadow.VERSION;
        item.activeScore = activeItem.systemScore;
        item.shadowScore = shadow.systemScore();
        item.activeAction = activeItem.finalAction;
        item.shadowAction = shadow.action();
        item.activeRiskScore = activeItem.riskScore;
        item.shadowRiskScore = shadow.riskScore();
        item.inputFingerprint = activeItem.inputFingerprint;
        item.t1PredictionId = predictions == null || predictions.get(1) == null ? null : predictions.get(1).id;
        item.t2PredictionId = predictions == null || predictions.get(2) == null ? null : predictions.get(2).id;
        item.t3PredictionId = predictions == null || predictions.get(3) == null ? null : predictions.get(3).id;
        item.evaluationStatus = shadow.unavailableReason() == null ? "PENDING_TN_LABEL" : "DATA_UNAVAILABLE";
        item.createdAt = LocalDateTime.now();
        mapper.insertIgnore(item);
    }
}
