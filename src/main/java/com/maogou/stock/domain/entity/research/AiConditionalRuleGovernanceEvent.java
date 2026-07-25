package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Immutable governance history for conditional rules; intentionally separate from model releases. */
@TableName("ai_conditional_rule_governance_event")
public class AiConditionalRuleGovernanceEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long tradeRuleConfigId;
    public Long experimentId;
    public Long shadowObservationId;
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
