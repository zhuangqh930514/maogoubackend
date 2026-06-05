package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_prediction_sample")
public class AiPredictionSample {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String stockCode;
    public String stockName;
    public LocalDateTime sampleTime;
    public LocalDate tradeDate;
    public String samplePhase;
    public String universeCode;
    public String marketRegime;
    public String sectorCode;
    public String sectorName;
    public BigDecimal dataQualityScore;
    public Integer tradable;
    public String excludeReason;
    public String featureSnapshot;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
