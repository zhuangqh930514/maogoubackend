package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_daily_insight_snapshot")
public class AiDailyInsightSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public LocalDate tradeDate;
    public LocalDateTime generatedAt;
    public String pipelineStatus;
    public String pipelineMessage;
    public String freshnessStatus;
    public BigDecimal dataQualityScore;
    public Integer recommendationCount;
    public Integer avoidCount;
    public Integer watchCount;
    public Integer itemCount;
    public Integer lowSampleCount;
    public BigDecimal overallHitRate;
    public LocalDateTime latestReportAt;
    public LocalDateTime latestSampleAt;
    public Long latestJobLogId;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
