package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiConditionalRuleShadowObservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiConditionalRuleShadowObservationMapper extends BaseMapper<AiConditionalRuleShadowObservation> {

    @Insert("""
            INSERT INTO ai_conditional_rule_shadow_observation (
                user_id, experiment_id, baseline_trade_rule_config_id, candidate_trade_rule_config_id,
                observation_key, horizon_days, window_start_date, window_end_date, eligible_sample_count,
                baseline_triggered_count, candidate_triggered_count, status, metrics_json,
                threshold_snapshot_json, input_fingerprint, observed_at, created_at, updated_at
            ) VALUES (
                #{item.userId}, #{item.experimentId}, #{item.baselineTradeRuleConfigId},
                #{item.candidateTradeRuleConfigId}, #{item.observationKey}, #{item.horizonDays},
                #{item.windowStartDate}, #{item.windowEndDate}, #{item.eligibleSampleCount},
                #{item.baselineTriggeredCount}, #{item.candidateTriggeredCount}, #{item.status},
                #{item.metricsJson}, #{item.thresholdSnapshotJson}, #{item.inputFingerprint},
                #{item.observedAt}, #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiConditionalRuleShadowObservation item);

    @Select("SELECT * FROM ai_conditional_rule_shadow_observation WHERE observation_key = #{key} FOR SHARE")
    AiConditionalRuleShadowObservation selectByObservationKeyForShare(@Param("key") String key);

    @Select("SELECT * FROM ai_conditional_rule_shadow_observation WHERE id = #{id} FOR UPDATE")
    AiConditionalRuleShadowObservation selectByIdForUpdate(@Param("id") Long id);
}
