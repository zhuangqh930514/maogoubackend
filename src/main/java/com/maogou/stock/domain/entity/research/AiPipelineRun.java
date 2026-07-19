package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_pipeline_run")
public class AiPipelineRun {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String scopeType;
    public Long ownerUserId;
    public Long parentRunId;
    public Long dataBatchId;
    public Long strategyReleaseId;
    public Long modelVersionId;
    public LocalDate tradeDate;
    public String pipelineType;
    public String idempotencyKey;
    public String inputFingerprint;
    public String status;
    public String executionOwner;
    public LocalDateTime leaseUntil;
    public LocalDateTime nextRetryAt;
    public String currentStep;
    public Integer retryCount;
    public Integer processedCount;
    public Integer successCount;
    public Integer failedCount;
    public String errorMessage;
    public String errorDetail;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
