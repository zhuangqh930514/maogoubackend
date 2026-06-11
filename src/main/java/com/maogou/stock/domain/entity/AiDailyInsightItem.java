package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_daily_insight_item")
public class AiDailyInsightItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long snapshotId;
    public Long userId;
    public LocalDate tradeDate;
    public String stockCode;
    public String stockName;
    public String finalAction;
    public String actionBucket;
    public BigDecimal compositeScore;
    public BigDecimal systemScore;
    public String aiDecision;
    public BigDecimal aiConfidence;
    public String targetDirection;
    public String riskLevel;
    public BigDecimal riskScore;
    public BigDecimal dataQualityScore;
    public BigDecimal freshnessScore;
    public String freshnessStatus;
    public String freshnessMessage;
    public BigDecimal historicalHitRate;
    public Integer historicalSampleCount;
    public String confidenceLevel;
    public String triggerFactorsJson;
    public String reasonSummary;
    public Long reportId;
    public Long predictionId;
    public Long sampleId;
    public LocalDateTime reportGeneratedAt;
    public LocalDateTime sampleTime;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
