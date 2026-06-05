package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_strategy_experiment")
public class AiStrategyExperiment {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String title;
    public String status;
    public String universeCode;
    public LocalDate trainStartDate;
    public LocalDate trainEndDate;
    public LocalDate validationStartDate;
    public LocalDate validationEndDate;
    public LocalDate testStartDate;
    public LocalDate testEndDate;
    public String configJson;
    public String metricsJson;
    public String baselineMetricsJson;
    public Integer canPromote;
    public Long promotedStrategyVersionId;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
