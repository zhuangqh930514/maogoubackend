package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_walk_forward_run")
public class AiWalkForwardRun {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long trainingDatasetId;
    public Long strategyReleaseId;
    public Long modelVersionId;
    public String runKey;
    public String runVersion;
    public String objective;
    public Integer horizonDays;
    public Integer purgeDays;
    public Integer embargoDays;
    public Integer foldCount;
    public Long randomSeed;
    public String inputFingerprint;
    public String configJson;
    public String aggregateMetricsJson;
    public String status;
    public LocalDateTime startedAt;
    public LocalDateTime completedAt;
    public LocalDateTime createdAt;
}
