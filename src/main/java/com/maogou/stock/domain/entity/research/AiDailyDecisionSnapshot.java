package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_daily_decision_snapshot")
public class AiDailyDecisionSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public LocalDate tradeDate;
    public Integer snapshotVersion;
    public Long pipelineRunId;
    public Long globalPipelineRunId;
    public Long strategyReleaseId;
    public Long modelVersionId;
    public Long supersedesSnapshotId;
    public String idempotencyKey;
    public Integer isCurrent;
    public String snapshotStatus;
    public String marketRegime;
    public Integer recommendationCount;
    public Integer cautiousCount;
    public Integer avoidCount;
    public Integer holdingRiskCount;
    public Integer unavailableCount;
    public BigDecimal overallHitRate;
    public String freshnessStatus;
    public BigDecimal dataQualityScore;
    public String decisionPolicyVersion;
    public String summaryJson;
    public LocalDateTime generatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
