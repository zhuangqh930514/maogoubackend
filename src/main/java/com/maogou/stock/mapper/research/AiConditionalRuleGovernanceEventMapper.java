package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiConditionalRuleGovernanceEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiConditionalRuleGovernanceEventMapper extends BaseMapper<AiConditionalRuleGovernanceEvent> {

    @Insert("""
            INSERT INTO ai_conditional_rule_governance_event (
                user_id, trade_rule_config_id, experiment_id, shadow_observation_id, event_key,
                event_type, decision_status, policy_version, actor_type, actor_user_id, reason,
                threshold_snapshot_json, evidence_json, occurred_at, created_at
            ) VALUES (
                #{item.userId}, #{item.tradeRuleConfigId}, #{item.experimentId}, #{item.shadowObservationId},
                #{item.eventKey}, #{item.eventType}, #{item.decisionStatus}, #{item.policyVersion},
                #{item.actorType}, #{item.actorUserId}, #{item.reason}, #{item.thresholdSnapshotJson},
                #{item.evidenceJson}, #{item.occurredAt}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiConditionalRuleGovernanceEvent item);

    @Select("SELECT * FROM ai_conditional_rule_governance_event WHERE event_key = #{key} FOR SHARE")
    AiConditionalRuleGovernanceEvent selectByEventKeyForShare(@Param("key") String key);
}
