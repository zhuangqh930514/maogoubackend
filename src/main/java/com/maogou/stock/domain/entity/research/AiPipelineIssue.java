package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A structured, user-visible failure fact. It is deliberately separate from
 * log text so retries and stock-level failures remain queryable after restart.
 */
@TableName("ai_pipeline_issue")
public class AiPipelineIssue {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long reportId;
    public Long pipelineRunId;
    public LocalDate tradeDate;
    public String stepKey;
    public String stepName;
    public String stockCode;
    public String stockName;
    public String providerCode;
    public String endpointType;
    public String reasonCode;
    public String reasonMessage;
    public Integer retryCount;
    public Integer maxRetries;
    public LocalDateTime nextRetryAt;
    public Integer recoverable;
    public LocalDateTime sourceAsOf;
    public Integer attemptNo;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
