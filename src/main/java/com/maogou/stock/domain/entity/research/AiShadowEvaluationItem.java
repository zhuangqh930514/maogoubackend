package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_shadow_evaluation_item")
public class AiShadowEvaluationItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long shadowEvaluationId;
    public Long sampleId;
    public Long championPredictionId;
    public Long challengerPredictionId;
    @TableField("sample_label_id")
    public Long labelId;
    @TableField("horizon_trading_days")
    public Integer horizonDays;
    public Integer actionAgreement;
    public BigDecimal scoreDelta;
    public BigDecimal confidenceDelta;
    public BigDecimal challengerExcessReturn;
    public String evaluationStatus;
    public LocalDateTime createdAt;
}
