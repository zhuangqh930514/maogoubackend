package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_research_universe")
public class AiResearchUniverse {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String universeCode;
    public String universeName;
    public String marketCode;
    public String selectionPolicyJson;
    public Integer minimumStockCount;
    public Integer enabled;
    public String seedVersion;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
