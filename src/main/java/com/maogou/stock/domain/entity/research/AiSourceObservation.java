package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_source_observation")
public class AiSourceObservation {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long dataBatchId;
    public String stockCode;
    public String sourceType;
    public String providerCode;
    public String endpointType;
    public LocalDateTime eventTime;
    public LocalDateTime publishedAt;
    public LocalDateTime firstSeenAt;
    public LocalDateTime fetchedAt;
    public LocalDateTime asOfTime;
    public LocalDateTime availableAt;
    public LocalDateTime observedAt;
    public String sourceRevision;
    public String sourceUri;
    public String payloadJson;
    public String payloadChecksum;
    public String sourceFingerprint;
    public String freshnessStatus;
    public String qualityStatus;
    public String missingReason;
    public LocalDateTime createdAt;
}
