package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiConditionalRuleExperiment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiConditionalRuleExperimentMapper extends BaseMapper<AiConditionalRuleExperiment> {

    @Insert("""
            INSERT INTO ai_conditional_rule_experiment (
                user_id, trade_rule_config_id, experiment_key, rule_config_version, horizon_days,
                window_start_date, window_end_date, fold_count, status, candidate_status,
                eligible_sample_count, triggered_sample_count, config_snapshot_json,
                threshold_snapshot_json, aggregate_metrics_json, input_fingerprint, evaluated_at,
                created_at, updated_at
            ) VALUES (
                #{item.userId}, #{item.tradeRuleConfigId}, #{item.experimentKey}, #{item.ruleConfigVersion},
                #{item.horizonDays}, #{item.windowStartDate}, #{item.windowEndDate}, #{item.foldCount},
                #{item.status}, #{item.candidateStatus}, #{item.eligibleSampleCount}, #{item.triggeredSampleCount},
                #{item.configSnapshotJson}, #{item.thresholdSnapshotJson}, #{item.aggregateMetricsJson},
                #{item.inputFingerprint}, #{item.evaluatedAt}, #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiConditionalRuleExperiment item);

    @Select("SELECT * FROM ai_conditional_rule_experiment WHERE experiment_key = #{key} FOR SHARE")
    AiConditionalRuleExperiment selectByExperimentKeyForShare(@Param("key") String key);
}
