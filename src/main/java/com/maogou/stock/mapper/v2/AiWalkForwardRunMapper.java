package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiWalkForwardRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiWalkForwardRunMapper extends BaseMapper<AiWalkForwardRun> {

    @Insert("""
            INSERT INTO ai_walk_forward_run (
                user_id, training_dataset_id, strategy_release_id, model_version_id, run_key,
                run_version, objective, horizon_days, purge_days, embargo_days, fold_count,
                random_seed, input_fingerprint, config_json, aggregate_metrics_json, status,
                started_at, completed_at, created_at
            ) VALUES (
                #{item.userId}, #{item.trainingDatasetId}, #{item.strategyReleaseId},
                #{item.modelVersionId}, #{item.runKey}, #{item.runVersion}, #{item.objective},
                #{item.horizonDays}, #{item.purgeDays}, #{item.embargoDays}, #{item.foldCount},
                #{item.randomSeed}, #{item.inputFingerprint}, #{item.configJson},
                #{item.aggregateMetricsJson}, #{item.status}, #{item.startedAt},
                #{item.completedAt}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiWalkForwardRun item);

    @Select("""
            SELECT * FROM ai_walk_forward_run
            WHERE user_id = #{userId} AND run_key = #{runKey}
            FOR SHARE
            """)
    AiWalkForwardRun selectByRunKeyForShare(
            @Param("userId") Long userId,
            @Param("runKey") String runKey
    );
}
