package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiStrategyGovernanceEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiStrategyGovernanceEventMapper extends BaseMapper<AiStrategyGovernanceEvent> {

    @Insert("""
            INSERT INTO ai_strategy_governance_event (
                user_id, strategy_release_id, previous_champion_release_id, walk_forward_run_id,
                backtest_run_id, shadow_evaluation_id, event_key, event_type, decision_status,
                policy_version, actor_type, actor_id, reason, threshold_snapshot_json,
                evidence_json, occurred_at, created_at
            ) VALUES (
                #{item.userId}, #{item.strategyReleaseId}, #{item.previousChampionReleaseId},
                #{item.walkForwardRunId}, #{item.backtestRunId}, #{item.shadowEvaluationId},
                #{item.eventKey}, #{item.eventType}, #{item.decisionStatus}, #{item.policyVersion},
                #{item.actorType}, #{item.actorId}, #{item.reason}, #{item.thresholdSnapshotJson},
                #{item.evidenceJson}, #{item.occurredAt}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiStrategyGovernanceEvent item);

    @Select("""
            SELECT * FROM ai_strategy_governance_event
            WHERE user_id = #{userId} AND event_key = #{eventKey}
            FOR SHARE
            """)
    AiStrategyGovernanceEvent selectByEventKeyForShare(
            @Param("userId") Long userId,
            @Param("eventKey") String eventKey
    );
}
