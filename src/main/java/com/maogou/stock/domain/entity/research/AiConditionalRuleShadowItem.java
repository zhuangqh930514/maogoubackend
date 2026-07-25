package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_conditional_rule_shadow_item")
public class AiConditionalRuleShadowItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long shadowObservationId;
    public Long sampleId;
    public Long sampleLabelId;
    public String stockCode;
    public LocalDate tradeDate;
    public Integer horizonDays;
    public String baselineRuleCode;
    public String baselineAction;
    public Integer baselineTriggered;
    public String candidateRuleCode;
    public String candidateAction;
    public Integer candidateTriggered;
    public BigDecimal realizedNetReturn;
    public BigDecimal realizedExcessReturn;
    public String featureFingerprint;
    public String labelFingerprint;
    public String evidenceJson;
    public LocalDateTime createdAt;
}
