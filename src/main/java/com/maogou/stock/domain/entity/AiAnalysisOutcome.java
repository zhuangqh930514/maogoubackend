package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_analysis_outcome")
public class AiAnalysisOutcome {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long reportId;
    public String stockCode;
    public String stockName;
    public LocalDate reportDate;
    public Integer horizonDays;
    public String predictionDirection;
    public String actualDirection;
    public BigDecimal entryPrice;
    public BigDecimal closePrice;
    public BigDecimal highPrice;
    public BigDecimal lowPrice;
    public BigDecimal pctChange;
    public BigDecimal maxDrawdown;
    public Integer directionCorrect;
    public Integer success;
    public BigDecimal successScore;
    public LocalDateTime evaluatedAt;
    @TableLogic
    public Integer deleted;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
