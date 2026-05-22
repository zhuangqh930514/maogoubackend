package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maogou.stock.domain.enums.AnalysisStatus;

import java.time.LocalDateTime;

@TableName("ai_analysis_report")
public class AiAnalysisReport {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String stockCode;
    public String stockName;
    public Integer score;
    public String advice;
    public String technicalAnalysis;
    public String riskWarning;
    public String buySellPoints;
    public String promptSummary;
    public String rawPrompt;
    public String rawResponse;
    public AnalysisStatus status;
    public String errorMessage;
    public LocalDateTime generatedAt;
    @TableLogic
    public Integer deleted;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
