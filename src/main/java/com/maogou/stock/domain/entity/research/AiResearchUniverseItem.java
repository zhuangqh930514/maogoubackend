package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_research_universe_item")
public class AiResearchUniverseItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long universeSnapshotId;
    public String stockCode;
    public String stockName;
    public String market;
    public String sectorCode;
    public String sectorName;
    public String industryCode;
    public String industryName;
    public String industryStandard;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    public Long sampleId;
    public String listedStatus;
    public String sourceType;
    public Integer included;
    public String inclusionReason;
    public String excludeReason;
    public LocalDate effectiveFrom;
    public LocalDate effectiveTo;
    public String evidenceJson;
    public String sourceFingerprint;
    public LocalDateTime createdAt;
}
