package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_data_batch")
public class AiDataBatch {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public LocalDate tradeDate;
    public String samplePhase;
    public LocalDateTime asOfTime;
    public String idempotencyKey;
    public String sourceStatus;
    public LocalDateTime quoteAsOf;
    public LocalDate klineAsOf;
    public LocalDate financeAsOf;
    public LocalDateTime sectorAsOf;
    public LocalDateTime newsAsOf;
    public BigDecimal qualityScore;
    public String qualityStatus;
    public Integer itemCount;
    public Integer successCount;
    public Integer failedCount;
    public String errorMessage;
    public String status;
    public LocalDateTime startedAt;
    public LocalDateTime completedAt;
    public LocalDateTime createdAt;
}
