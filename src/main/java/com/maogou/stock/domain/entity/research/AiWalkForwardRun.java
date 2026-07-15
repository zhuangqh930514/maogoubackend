package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_walk_forward_run")
public class AiWalkForwardRun {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long trainingDatasetId;
    public Long strategyReleaseId;
    public Long modelVersionId;
    public String runKey;
    public String engineVersion;
    public Integer purgeTradingDays;
    public Integer embargoTradingDays;
    public Long randomSeed;
    public String inputFingerprint;
    public String configJson;
    public String aggregateMetricsJson;
    public String status;
    public LocalDateTime startedAt;
    public LocalDateTime completedAt;
    public LocalDateTime createdAt;
}
