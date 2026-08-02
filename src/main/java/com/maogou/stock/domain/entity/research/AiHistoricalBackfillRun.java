package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_historical_backfill_run")
public class AiHistoricalBackfillRun {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long pipelineRunId;
    public String runKey;
    public String mode;
    public LocalDate requestedStartDate;
    public LocalDate requestedEndDate;
    public LocalDate effectiveSampleStartDate;
    public LocalDate effectiveSampleEndDate;
    public Integer targetTradingDays;
    public Integer targetStocksPerDay;
    public String featureVersion;
    public String factorVersion;
    public String labelVersion;
    public String calendarVersion;
    public String industryStandard;
    public String sourceManifestChecksum;
    public String runConfigJson;
    public String status;
    public String currentStage;
    public Integer totalShards;
    public Integer succeededShards;
    public Integer quarantinedShards;
    public Integer failedShards;
    public Long readinessSnapshotId;
    public String leaseOwner;
    public LocalDateTime leaseUntil;
    public LocalDateTime lastHeartbeatAt;
    public String errorSummary;
    public Long requestedBy;
    public LocalDateTime createdAt;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public LocalDateTime updatedAt;
}
