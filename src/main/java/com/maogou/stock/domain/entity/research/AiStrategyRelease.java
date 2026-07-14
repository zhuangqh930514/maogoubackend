package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_strategy_release")
public class AiStrategyRelease {
    @TableId(type = IdType.AUTO)
    public Long id;
    @TableField(exist = false)
    public Long userId;
    public Long researchUniverseId;
    public String modelFamily;
    public String versionNo;
    public String title;
    public Long modelVersionId;
    public String status;
    public String releaseRole;
    @TableField(exist = false)
    public Integer activeChampionGuard;
    public String configJson;
    public String factorSnapshotJson;
    public String validationMetricsJson;
    public String promotionReason;
    public String rollbackReason;
    public String seedVersion;
    public LocalDateTime shadowStartedAt;
    public LocalDateTime shadowEndedAt;
    public LocalDateTime activatedAt;
    public LocalDateTime retiredAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
