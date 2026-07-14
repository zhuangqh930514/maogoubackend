package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiWalkForwardBaseline;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiWalkForwardBaselineMapper extends BaseMapper<AiWalkForwardBaseline> {

    @Insert("""
            <script>
            INSERT INTO ai_walk_forward_baseline (
                walk_forward_fold_id, strategy_release_id, baseline_key, baseline_type,
                benchmark_code, metrics_json, nav_json, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.walkForwardFoldId}, #{item.strategyReleaseId}, #{item.baselineKey},
                    #{item.baselineType}, #{item.benchmarkCode}, #{item.metricsJson},
                    #{item.navJson}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiWalkForwardBaseline> items);

    @Select("""
            SELECT baseline.* FROM ai_walk_forward_baseline baseline
            JOIN ai_walk_forward_fold fold ON fold.id = baseline.walk_forward_fold_id
            WHERE fold.walk_forward_run_id = #{runId}
            ORDER BY fold.fold_no, baseline.baseline_key
            FOR SHARE
            """)
    List<AiWalkForwardBaseline> selectByRunIdForShare(@Param("runId") Long runId);
}
