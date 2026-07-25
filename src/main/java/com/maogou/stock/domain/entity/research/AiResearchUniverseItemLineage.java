package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Immutable provenance captured when a user-owned record contributes to a research universe item.
 * It deliberately describes the source at snapshot time instead of joining the mutable source row later.
 */
@TableName("ai_research_universe_item_lineage")
public class AiResearchUniverseItemLineage {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long universeItemId;
    public String sourceType;
    public Long ownerUserId;
    public Long sourceRecordId;
    public Integer activeAtSnapshot;
    public String sourceFingerprint;
    public String evidenceJson;
    public LocalDateTime observedAt;
    public LocalDateTime createdAt;
}
