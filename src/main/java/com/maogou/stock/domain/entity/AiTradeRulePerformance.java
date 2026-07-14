package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_trade_rule_performance")
public class AiTradeRulePerformance {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long tradeRuleConfigId;
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
    public BigDecimal avgPostTriggerReturn;
    public BigDecimal avgAdverseReturn;
    public BigDecimal learnedWeight;
    public String confidenceLevel;
    public String inputFingerprint;
    public LocalDateTime lastEvaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
