package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Immutable-per-run SLA snapshot for a due learning horizon. */
@TableName("ai_learning_coverage_daily")
public class AiLearningCoverageDaily {
    @TableId(type = IdType.AUTO)
    public Long id;
    public LocalDate tradeDate;
    public Integer horizonTradingDays;
    public Long pipelineRunId;
    public Integer eligiblePredictionCount;
    public Integer matureLabelCount;
    public Integer evaluationCount;
    public Integer directionAssessedCount;
    public Integer planDueCount;
    public Integer planTriggerCheckedCount;
    public Integer planOutcomeEvaluatedCount;
    public Integer unavailableCount;
    public Integer retryableCount;
    public Integer failedCount;
    public BigDecimal coverageRate;
    public String coverageStatus;
    public String errorSummary;
    public LocalDateTime generatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
