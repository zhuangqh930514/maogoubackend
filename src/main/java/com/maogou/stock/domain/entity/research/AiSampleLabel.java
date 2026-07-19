package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_sample_label")
public class AiSampleLabel {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long sampleId;
    public Long entryCalendarId;
    public Long exitCalendarId;
    public String stockCode;
    public Integer horizonTradingDays;
    public String labelVersion;
    public Integer revisionNo;
    public Integer isCurrent;
    public Long supersedesLabelId;
    public String revisionReason;
    public String calendarVersion;
    public String inputFingerprint;
    public LocalDate entryTradeDate;
    public LocalDate plannedExitTradeDate;
    public LocalDate exitTradeDate;
    public Integer exitDelayTradingDays;
    public BigDecimal entryPrice;
    public BigDecimal exitPrice;
    public BigDecimal grossReturn;
    public BigDecimal netReturn;
    public BigDecimal benchmarkReturn;
    public BigDecimal sectorReturn;
    public BigDecimal excessReturn;
    public BigDecimal sectorExcessReturn;
    public String sectorMembershipFingerprint;
    public BigDecimal maxFavorableReturn;
    public BigDecimal maxAdverseReturn;
    public BigDecimal maxDrawdown;
    public BigDecimal holdingVolatility;
    public Integer holdingTradingDays;
    public String actualDirection;
    public String fillStatus;
    public String executionStatus;
    public String executionReason;
    public String labelStatus;
    public String policySnapshotJson;
    public String marketEvidenceJson;
    public LocalDateTime labelAvailableAt;
    public LocalDateTime maturedAt;
    public LocalDateTime verifiedAt;
    public LocalDateTime createdAt;
}
