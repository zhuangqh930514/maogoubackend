package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_watchlist_analysis_job")
public class AiWatchlistAnalysisJob {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long promptTemplateId;
    public String status;
    public String activeKey;
    public Integer totalCount;
    public Integer completedCount;
    public Integer analyzedCount;
    public Integer skippedCount;
    public Integer failedCount;
    public String currentStockCode;
    public String currentStockName;
    public String message;
    public String lastError;
    public String issueDetails;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
