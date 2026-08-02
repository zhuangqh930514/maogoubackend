package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_data_quarantine")
public class AiDataQuarantine {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long backfillRunId;
    public Long shardId;
    public String providerCode;
    public String datasetCode;
    public LocalDate tradeDate;
    public String stockCode;
    public String industryCode;
    @TableField("source_row_number")
    public Long rowNumber;
    public String fieldName;
    public String reasonCode;
    public String reasonMessage;
    public String rawFingerprint;
    public String quarantineFingerprint;
    public Integer retryable;
    public String resolutionStatus;
    public LocalDateTime createdAt;
    public LocalDateTime resolvedAt;
}
