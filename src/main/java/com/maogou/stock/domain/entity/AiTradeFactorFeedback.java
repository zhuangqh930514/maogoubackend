package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Candidate-only conditional-plan evidence. It never mutates formal factor labels. */
@TableName("ai_trade_factor_feedback")
public class AiTradeFactorFeedback {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long tradeRuleConfigId;
    public String factorCode;
    public String factorName;
    public String factorGroup;
    public String ruleCode;
    public String ruleType;
    @TableField("horizon_trading_days")
    public Integer horizonDays;
    public String marketRegime;
    public LocalDate windowStartDate;
    public LocalDate windowEndDate;
    public Integer sampleCount;
    public Integer effectiveCount;
    public BigDecimal effectivenessRate;
    public BigDecimal avgNetActionReturn;
    public BigDecimal avgExcessReturn;
    public String confidenceLevel;
    public String feedbackScope;
    public String inputFingerprint;
    public LocalDateTime lastEvaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
