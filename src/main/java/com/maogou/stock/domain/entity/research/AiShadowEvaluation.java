package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_shadow_evaluation")
public class AiShadowEvaluation {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long pipelineRunId;
    public Long trainingDatasetId;
    public Long championReleaseId;
    public Long challengerReleaseId;
    public Long championModelVersionId;
    public Long challengerModelVersionId;
    public LocalDate windowStartDate;
    public LocalDate windowEndDate;
    public String evaluationVersion;
    public String inputFingerprint;
    public Integer sampleCount;
    public Integer eligibleSampleCount;
    public BigDecimal coverageRate;
    public BigDecimal actionAgreementRate;
    public BigDecimal championCalibrationError;
    public BigDecimal challengerCalibrationError;
    public BigDecimal championExcessReturn;
    public BigDecimal challengerExcessReturn;
    public BigDecimal championMaxDrawdown;
    public BigDecimal challengerMaxDrawdown;
    public BigDecimal featureDriftScore;
    public String metricsJson;
    public String decisionStatus;
    public LocalDateTime evaluatedAt;
    public LocalDateTime createdAt;
}
