package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Immutable, point-in-time daily trading constraints for one A-share security. */
@TableName("ai_security_daily_state")
public class AiSecurityDailyState {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String stockCode;
    public LocalDate tradeDate;
    public Long sourceBatchId;
    public String sourceRevision;
    public Integer revisionNo;
    public Integer isCurrent;
    public Long supersedesStateId;
    public LocalDate listedOn;
    public Integer listedDays;
    public String securityStatus;
    public String stStatus;
    public Integer isSt;
    public Integer suspended;
    public BigDecimal limitRatio;
    public BigDecimal limitUpPrice;
    public BigDecimal limitDownPrice;
    public Integer isLimitUp;
    public Integer isLimitDown;
    public Integer buyTradable;
    public Integer sellTradable;
    public String qualityStatus;
    public String missingReason;
    public String evidenceJson;
    public String sourceFingerprint;
    public LocalDateTime observedAt;
    public LocalDateTime createdAt;
    @TableField(exist = false)
    public Integer currentGuard;
}
