package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_daily_decision_item")
public class AiDailyDecisionItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long decisionSnapshotId;
    public LocalDate tradeDate;
    public Long sampleId;
    public Long reportId;
    public String stockCode;
    public String stockName;
    public String category;
    public BigDecimal systemScore;
    public BigDecimal horizonSignalScore;
    public BigDecimal factorReliabilityScore;
    public BigDecimal strategyValidationScore;
    public BigDecimal dataQualityComponent;
    public BigDecimal riskComponent;
    public String finalAction;
    public BigDecimal riskScore;
    public String riskLevel;
    public String decisionSource;
    public String freshnessStatus;
    public String decisionPolicyVersion;
    public String confidenceLevel;
    public Integer outOfSampleCount;
    public BigDecimal historicalHitRate;
    public String triggerFactorsJson;
    public String reasonSummary;
    public String unavailableReason;
    public String inputFingerprint;
    public LocalDateTime createdAt;
}
