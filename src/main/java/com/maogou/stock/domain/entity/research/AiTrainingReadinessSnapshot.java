package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_training_readiness_snapshot")
public class AiTrainingReadinessSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long backfillRunId;
    public Long pipelineRunId;
    public LocalDateTime asOfTime;
    public String featureVersion;
    public String factorVersion;
    public String labelVersion;
    public String calendarVersion;
    public Integer tradingDays;
    public Integer stockCount;
    public String horizonCountsJson;
    public String regimeDaysJson;
    public Integer tradabilityEligible;
    public Integer tradabilityReady;
    public BigDecimal tradabilityCoverage;
    public Integer universeEligible;
    public Integer universeReady;
    public BigDecimal universeCoverage;
    public Integer sectorEligible;
    public Integer sectorReady;
    public BigDecimal sectorCoverage;
    public String featureCoverageJson;
    public String classDistributionJson;
    public Integer leakageViolationCount;
    public Integer duplicateCount;
    public Integer mockSourceCount;
    public Integer staleSourceCount;
    public Integer inferredFactCount;
    public String status;
    public String blockingGapsJson;
    public String evidenceChecksum;
    public LocalDateTime createdAt;
}
