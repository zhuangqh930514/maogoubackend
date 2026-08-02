package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_historical_backfill_shard")
public class AiHistoricalBackfillShard {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long backfillRunId;
    public String stageKey;
    public LocalDate tradeDate;
    public Integer bucketNo;
    public String idempotencyKey;
    public String status;
    public Integer attemptNo;
    public Integer maxAttempts;
    public Integer inputCount;
    public Integer outputCount;
    public Integer rejectedCount;
    public String checkpointJson;
    public String inputFingerprint;
    public String outputFingerprint;
    public String providerCode;
    public String endpointType;
    public LocalDateTime nextRetryAt;
    public String leaseOwner;
    public LocalDateTime leaseUntil;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public String errorCode;
    public String errorMessage;
    public String errorDetail;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
