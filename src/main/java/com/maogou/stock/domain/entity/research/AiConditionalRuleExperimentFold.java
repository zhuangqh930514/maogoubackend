package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_conditional_rule_experiment_fold")
public class AiConditionalRuleExperimentFold {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long experimentId;
    public Integer foldNo;
    public LocalDate trainStartDate;
    public LocalDate trainEndDate;
    public LocalDate validationStartDate;
    public LocalDate validationEndDate;
    public LocalDate testStartDate;
    public LocalDate testEndDate;
    public Integer trainEligibleCount;
    public Integer validationEligibleCount;
    public Integer testEligibleCount;
    public Integer testTriggeredCount;
    public String metricsJson;
    public String inputFingerprint;
    public String status;
    public LocalDateTime createdAt;
}
