package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_daily_decision_plan_review")
public class AiDailyDecisionPlanReview {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long decisionPlanId;
    public LocalDate triggerTradeDate;
    public LocalDate outcomeTradeDate;
    public String status;
    public String triggeredRuleCode;
    public String triggeredState;
    public String suggestedAction;
    public BigDecimal triggerPrice;
    public BigDecimal outcomePrice;
    public BigDecimal postTriggerReturn;
    public BigDecimal maxFavorableReturn;
    public BigDecimal maxAdverseReturn;
    public BigDecimal transactionCostBps;
    public BigDecimal netActionReturn;
    public BigDecimal benchmarkReturn;
    public BigDecimal excessReturn;
    public Integer actionEffective;
    public BigDecimal reviewScore;
    public String actualMetricsJson;
    public String feedbackJson;
    public String feedbackSummary;
    public LocalDateTime triggerCheckedAt;
    public LocalDateTime outcomeCheckedAt;
    public String triggerSourceProvider;
    public String triggerSourceFingerprint;
    public String outcomeSourceProvider;
    public String outcomeSourceFingerprint;
    public Integer retryCount;
    public LocalDateTime nextRetryAt;
    public LocalDateTime evaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
