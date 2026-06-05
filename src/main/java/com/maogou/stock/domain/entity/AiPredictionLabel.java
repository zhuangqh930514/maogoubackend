package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_prediction_label")
public class AiPredictionLabel {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long predictionId;
    public Long sampleId;
    public String stockCode;
    public Integer horizonDays;
    public BigDecimal entryPrice;
    public BigDecimal exitPrice;
    public BigDecimal closeReturn;
    public BigDecimal maxFavorableReturn;
    public BigDecimal maxAdverseReturn;
    public BigDecimal benchmarkReturn;
    public BigDecimal sectorReturn;
    public BigDecimal excessReturn;
    public BigDecimal netReturn;
    public Integer hitDirection;
    public Integer hitTarget;
    public Integer hitStopLoss;
    public Integer tradable;
    public BigDecimal labelScore;
    public String labelStatus;
    public LocalDateTime evaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
