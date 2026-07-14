package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_trade_plan_review")
public class AiTradePlanReview {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long reportId;
    public String stockCode;
    public LocalDate reportDate;
    public Integer horizonDays;
    public LocalDate targetTradeDate;
    public LocalDate outcomeTradeDate;
    public String status;
    public String triggeredRuleCode;
    public String ruleType;
    public String triggeredState;
    public String suggestedAction;
    public String marketRegime;
    public BigDecimal triggerPrice;
    public BigDecimal outcomePrice;
    public BigDecimal postTriggerReturn;
    public BigDecimal maxFavorableReturn;
    public BigDecimal maxAdverseReturn;
    public Integer actionEffective;
    public BigDecimal reviewScore;
    public String actualMetricsJson;
    public String feedbackJson;
    public String feedbackSummary;
    public LocalDateTime evaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
