package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_source_health")
public class AiSourceHealth {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String providerCode;
    public String endpointType;
    public String sourceStatus;
    public LocalDateTime lastAttemptAt;
    public LocalDateTime lastSuccessAt;
    public Integer consecutiveFailureCount;
    public LocalDateTime cooldownUntil;
    public String lastErrorMessage;
    public String lastResponseFingerprint;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
