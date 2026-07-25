package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Immutable time-series evaluation for a candidate conditional-trade rule configuration. */
@TableName("ai_conditional_rule_experiment")
public class AiConditionalRuleExperiment {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long tradeRuleConfigId;
    public String experimentKey;
    public String ruleConfigVersion;
    public Integer horizonDays;
    public LocalDate windowStartDate;
    public LocalDate windowEndDate;
    public Integer foldCount;
    public String status;
    public String candidateStatus;
    public Integer eligibleSampleCount;
    public Integer triggeredSampleCount;
    public String configSnapshotJson;
    public String thresholdSnapshotJson;
    public String aggregateMetricsJson;
    public String inputFingerprint;
    public LocalDateTime evaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
