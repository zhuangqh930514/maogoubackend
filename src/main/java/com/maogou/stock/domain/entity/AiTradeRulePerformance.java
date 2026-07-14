package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_trade_rule_performance")
public class AiTradeRulePerformance {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String ruleCode;
    public String ruleType;
    public Integer horizonDays;
    public String marketRegime;
    public Integer sampleCount;
    public Integer effectiveCount;
    public BigDecimal effectivenessRate;
    public BigDecimal avgPostTriggerReturn;
    public BigDecimal avgAdverseReturn;
    public BigDecimal learnedWeight;
    public String confidenceLevel;
    public LocalDateTime lastEvaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
