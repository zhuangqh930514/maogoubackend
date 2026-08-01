package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_decision_policy_shadow_item")
public class AiDecisionPolicyShadowItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public LocalDate tradeDate;
    public Long sampleId;
    public String stockCode;
    public String activePolicyVersion;
    public String shadowPolicyVersion;
    public BigDecimal activeScore;
    public BigDecimal shadowScore;
    public String activeAction;
    public String shadowAction;
    public BigDecimal activeRiskScore;
    public BigDecimal shadowRiskScore;
    public String inputFingerprint;
    public Long t1PredictionId;
    public Long t2PredictionId;
    public Long t3PredictionId;
    public String evaluationStatus;
    public LocalDateTime createdAt;
}
