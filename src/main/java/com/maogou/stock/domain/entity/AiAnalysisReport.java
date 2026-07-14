package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maogou.stock.domain.enums.AnalysisStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_analysis_report")
public class AiAnalysisReport {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String stockCode;
    public String stockName;
    public Long sampleId;
    public Long strategyReleaseId;
    public Long promptTemplateId;
    public LocalDate reportDate;
    public Integer reportVersion;
    public Long supersedesReportId;
    public String idempotencyKey;
    public AnalysisStatus status;
    public BigDecimal systemScore;
    public String finalAction;
    public String targetDirection;
    public BigDecimal riskScore;
    public String riskLevel;
    public BigDecimal calibratedConfidence;
    public BigDecimal dataQualityScore;
    public String advice;
    public String technicalAnalysis;
    public String riskWarning;
    public String buySellPoints;
    public String conditionalStrategy;
    public String promptSummary;
    public String rawPrompt;
    public String rawResponse;
    public String sourceModel;
    public String errorMessage;
    public LocalDateTime generatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
