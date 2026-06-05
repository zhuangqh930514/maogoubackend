package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_model_eval_run")
public class AiModelEvalRun {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String modelName;
    public String provider;
    public Long promptTemplateId;
    public String evalType;
    public BigDecimal jsonSuccessRate;
    public BigDecimal avgLatencyMs;
    public Integer sampleCount;
    public BigDecimal score;
    public String metricsJson;
    public String status;
    public LocalDateTime createdAt;
}
