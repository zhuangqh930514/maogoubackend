package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_drift_event")
public class AiDriftEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long factorPerformanceId;
    public Long modelVersionId;
    public Long strategyReleaseId;
    public Long shadowEvaluationId;
    public String eventFingerprint;
    public String eventType;
    public String subjectType;
    public String subjectKey;
    public String detectorVersion;
    public LocalDate windowStartDate;
    public LocalDate windowEndDate;
    public String metricName;
    public BigDecimal baselineValue;
    public BigDecimal observedValue;
    public BigDecimal thresholdValue;
    public String severity;
    public String status;
    public String evidenceJson;
    public LocalDateTime detectedAt;
    public LocalDateTime acknowledgedAt;
    public LocalDateTime resolvedAt;
    public LocalDateTime createdAt;
}
