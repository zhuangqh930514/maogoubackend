package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_factor_performance")
public class AiFactorPerformance {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long factorDefinitionId;
    @TableField(exist = false)
    public Long userId;
    @TableField(exist = false)
    public String factorCode;
    @TableField(exist = false)
    public String factorName;
    @TableField(exist = false)
    public String factorVersion;
    @TableField("horizon_trading_days")
    public Integer horizonDays;
    public String marketRegime;
    public String windowType;
    public LocalDate windowStartDate;
    public LocalDate windowEndDate;
    public String inputFingerprint;
    public Integer sampleCount;
    public Integer successCount;
    public BigDecimal successRate;
    public BigDecimal wilsonLowerBound;
    public BigDecimal rankIc;
    public BigDecimal avgExcessReturn;
    public BigDecimal avgAdverseReturn;
    public BigDecimal stabilityScore;
    public BigDecimal psiScore;
    public String confidenceLevel;
    public String driftStatus;
    public LocalDateTime evaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
