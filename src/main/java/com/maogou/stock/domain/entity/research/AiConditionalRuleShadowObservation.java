package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Out-of-sample comparison of a candidate rule config against the currently active config. */
@TableName("ai_conditional_rule_shadow_observation")
public class AiConditionalRuleShadowObservation {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long experimentId;
    public Long baselineTradeRuleConfigId;
    public Long candidateTradeRuleConfigId;
    public String observationKey;
    public Integer horizonDays;
    public LocalDate windowStartDate;
    public LocalDate windowEndDate;
    public Integer eligibleSampleCount;
    public Integer baselineTriggeredCount;
    public Integer candidateTriggeredCount;
    public String status;
    public String metricsJson;
    public String thresholdSnapshotJson;
    public String inputFingerprint;
    public LocalDateTime observedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
