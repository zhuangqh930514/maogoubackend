package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiWalkForwardRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiWalkForwardRunMapper extends BaseMapper<AiWalkForwardRun> {

    @Insert("""
            INSERT INTO ai_walk_forward_run (
                training_dataset_id, strategy_release_id, model_version_id, run_key,
                engine_version, purge_trading_days, embargo_trading_days,
                random_seed, input_fingerprint, config_json, aggregate_metrics_json, status,
                started_at, completed_at, created_at
            ) VALUES (
                #{item.trainingDatasetId}, #{item.strategyReleaseId},
                #{item.modelVersionId}, #{item.runKey}, #{item.engineVersion},
                #{item.purgeTradingDays}, #{item.embargoTradingDays},
                #{item.randomSeed}, #{item.inputFingerprint}, #{item.configJson},
                #{item.aggregateMetricsJson}, #{item.status}, #{item.startedAt},
                #{item.completedAt}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiWalkForwardRun item);

    @Select("""
            SELECT * FROM ai_walk_forward_run
            WHERE run_key = #{runKey}
            FOR SHARE
            """)
    AiWalkForwardRun selectByRunKeyForShare(@Param("runKey") String runKey);
}
