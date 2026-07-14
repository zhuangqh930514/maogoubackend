package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_walk_forward_baseline")
public class AiWalkForwardBaseline {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long walkForwardFoldId;
    public Long strategyReleaseId;
    public String baselineKey;
    public String baselineType;
    public String benchmarkCode;
    public String metricsJson;
    public String navJson;
    public LocalDateTime createdAt;
}
