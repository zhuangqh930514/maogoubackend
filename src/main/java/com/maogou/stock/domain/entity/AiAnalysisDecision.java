package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_analysis_decision")
public class AiAnalysisDecision {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long reportId;
    public String stockCode;
    public String stockName;
    public String decision;
    public BigDecimal confidence;
    public String holdingPeriod;
    public String targetDirection;
    public String riskLevel;
    public String summary;
    public String factorsJson;
    public String rawDecisionJson;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
