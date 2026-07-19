package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_industry_daily_bar")
public class AiIndustryDailyBar {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String industryCode;
    public String industryName;
    public String classificationStandard;
    public LocalDate tradeDate;
    public BigDecimal openPrice;
    public BigDecimal highPrice;
    public BigDecimal lowPrice;
    public BigDecimal closePrice;
    public BigDecimal volume;
    public BigDecimal amount;
    public String sourceName;
    public String sourceRevision;
    public Integer revisionNo;
    public Integer isCurrent;
    public Long supersedesBarId;
    public String qualityStatus;
    public String sourceRef;
    public String evidenceJson;
    public String sourceFingerprint;
    public LocalDateTime observedAt;
    public LocalDateTime createdAt;
}
