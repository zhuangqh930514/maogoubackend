package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Per-sample evidence for a candidate rule experiment. No output is inferred from missing fields. */
@TableName("ai_conditional_rule_experiment_item")
public class AiConditionalRuleExperimentItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long experimentId;
    public Long experimentFoldId;
    public Long sampleId;
    public Long sampleLabelId;
    public String stockCode;
    public LocalDate tradeDate;
    public Integer horizonDays;
    public String evaluationPartition;
    public String ruleCode;
    public String suggestedAction;
    public Integer triggered;
    public BigDecimal realizedNetReturn;
    public BigDecimal realizedExcessReturn;
    public Integer actionEffective;
    public String featureFingerprint;
    public String labelFingerprint;
    public String evidenceJson;
    public LocalDateTime createdAt;
}
