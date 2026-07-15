package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_strategy_governance_event")
public class AiStrategyGovernanceEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long strategyReleaseId;
    public Long previousChampionReleaseId;
    public Long walkForwardRunId;
    public Long backtestRunId;
    public Long shadowEvaluationId;
    public String eventKey;
    public String eventType;
    public String decisionStatus;
    public String policyVersion;
    public String actorType;
    public Long actorUserId;
    public String reason;
    public String thresholdSnapshotJson;
    public String evidenceJson;
    public LocalDateTime occurredAt;
    public LocalDateTime createdAt;
}
