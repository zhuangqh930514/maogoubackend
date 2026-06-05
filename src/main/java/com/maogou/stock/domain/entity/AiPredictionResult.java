package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_prediction_result")
public class AiPredictionResult {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long sampleId;
    public Long reportId;
    public Long strategyVersionId;
    public Long modelVersionId;
    public Long promptTemplateId;
    public String action;
    public String targetDirection;
    public Integer horizonDays;
    public BigDecimal confidence;
    public BigDecimal score;
    public Integer rankNo;
    public BigDecimal riskScore;
    public String reasonJson;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
