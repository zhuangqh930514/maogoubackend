package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiWalkForwardFold;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiWalkForwardFoldMapper extends BaseMapper<AiWalkForwardFold> {

    @Insert("""
            <script>
            INSERT INTO ai_walk_forward_fold (
                walk_forward_run_id, fold_no, train_start_date, train_end_date,
                validation_start_date, validation_end_date, test_start_date, test_end_date,
                purge_days, embargo_days, train_sample_count, validation_sample_count,
                test_sample_count, metrics_json, confidence_interval_json, status,
                started_at, completed_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.walkForwardRunId}, #{item.foldNo}, #{item.trainStartDate},
                    #{item.trainEndDate}, #{item.validationStartDate}, #{item.validationEndDate},
                    #{item.testStartDate}, #{item.testEndDate}, #{item.purgeDays},
                    #{item.embargoDays}, #{item.trainSampleCount}, #{item.validationSampleCount},
                    #{item.testSampleCount}, #{item.metricsJson}, #{item.confidenceIntervalJson},
                    #{item.status}, #{item.startedAt}, #{item.completedAt}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiWalkForwardFold> items);

    @Select("""
            SELECT * FROM ai_walk_forward_fold
            WHERE walk_forward_run_id = #{runId}
            ORDER BY fold_no
            FOR SHARE
            """)
    List<AiWalkForwardFold> selectByRunIdForShare(@Param("runId") Long runId);
}
