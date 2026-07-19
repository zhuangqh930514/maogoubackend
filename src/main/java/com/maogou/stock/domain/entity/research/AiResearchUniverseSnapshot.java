package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_research_universe_snapshot")
public class AiResearchUniverseSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long researchUniverseId;
    public LocalDate tradeDate;
    public LocalDateTime asOfTime;
    public String universeVersion;
    public String calendarVersion;
    public String membershipSourceName;
    public String membershipSourceRevision;
    public LocalDateTime sourceObservedAt;
    public String pointInTimeStatus;
    public String pointInTimeReason;
    public String sourceFingerprint;
    public Integer itemCount;
    public String qualityStatus;
    public String status;
    public LocalDateTime createdAt;
}
