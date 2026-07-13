package com.maogou.stock.domain.entity.v2;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_research_daily_report")
public class AiResearchDailyReport {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public LocalDate tradeDate;
    public Integer reportVersion;
    public Long pipelineRunId;
    public Long strategyReleaseId;
    public Long modelVersionId;
    public Long supersedesReportId;
    public String idempotencyKey;
    public Integer isCurrent;
    public String reportStatus;
    public String title;
    public String executiveSummary;
    public String marketRegime;
    public Integer recommendationCount;
    public Integer watchCount;
    public Integer avoidCount;
    public Integer holdingRiskCount;
    public String freshnessStatus;
    public BigDecimal dataQualityScore;
    public String contentJson;
    public String markdownContent;
    public LocalDateTime generatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
