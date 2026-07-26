package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Immutable conditional-plan snapshot for a formal daily decision that did not
 * receive a model report. It deliberately remains separate from report plans.
 */
@TableName("ai_daily_decision_plan")
public class AiDailyDecisionPlan {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long decisionItemId;
    public Long sampleId;
    public Long tradeRuleConfigId;
    public String stockCode;
    public LocalDate tradeDate;
    public Integer horizonDays;
    public String planSource;
    public String officialAction;
    public String status;
    public LocalDate targetTradeDate;
    public LocalDate outcomeTradeDate;
    public String planJson;
    public String inputFingerprint;
    public String sourceProvider;
    public LocalDateTime sourceAsOf;
    public String unavailableReason;
    public LocalDateTime triggerCheckedAt;
    public LocalDateTime outcomeCheckedAt;
    public String triggerSourceProvider;
    public String triggerSourceFingerprint;
    public String outcomeSourceProvider;
    public String outcomeSourceFingerprint;
    public Integer retryCount;
    public LocalDateTime nextRetryAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
