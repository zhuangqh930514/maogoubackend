package com.maogou.stock.domain.entity.v2;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_label_v2")
public class AiLabelV2 {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long predictionId;
    public Long sampleId;
    public Long entryCalendarId;
    public Long exitCalendarId;
    public String stockCode;
    public Integer horizonDays;
    public String labelVersion;
    public String calendarVersion;
    public String inputFingerprint;
    public LocalDate entryTradeDate;
    public LocalDate exitTradeDate;
    public BigDecimal entryPrice;
    public BigDecimal exitPrice;
    public BigDecimal grossReturn;
    public BigDecimal netReturn;
    public BigDecimal benchmarkReturn;
    public BigDecimal sectorReturn;
    public BigDecimal excessReturn;
    public BigDecimal maxFavorableReturn;
    public BigDecimal maxAdverseReturn;
    public String executionStatus;
    public String executionReason;
    public String actionEvaluation;
    public Integer hitDirection;
    public Integer hitTarget;
    public Integer hitStopLoss;
    public BigDecimal labelScore;
    public String labelStatus;
    public LocalDateTime maturedAt;
    public LocalDateTime verifiedAt;
    public LocalDateTime createdAt;
}
