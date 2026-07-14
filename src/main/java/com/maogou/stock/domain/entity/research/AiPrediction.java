package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_prediction")
public class AiPrediction {
    @TableId(type = IdType.AUTO)
    public Long id;
    @TableField(exist = false)
    public Long userId;
    public Long sampleId;
    public Long strategyReleaseId;
    public Long modelVersionId;
    public String stockCode;
    public LocalDate tradeDate;
    public String samplePhase;
    public String inferenceMode;
    public String inputFingerprint;
    @TableField("horizon_trading_days")
    public Integer horizonDays;
    public BigDecimal expectedReturn;
    public BigDecimal expectedExcessReturn;
    public BigDecimal probabilityUp;
    public BigDecimal probabilityDown;
    public BigDecimal calibratedConfidence;
    public BigDecimal score;
    public BigDecimal riskScore;
    public Integer rankNo;
    public String action;
    public String actionBucket;
    public String targetDirection;
    public String abstainReason;
    public String reasonJson;
    public String idempotencyKey;
    public LocalDateTime predictedAt;
    public LocalDateTime createdAt;
}
