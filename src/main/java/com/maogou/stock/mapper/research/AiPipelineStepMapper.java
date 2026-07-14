package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.LocalDateTime;

public interface AiPipelineStepMapper extends BaseMapper<AiPipelineStep> {

    @Insert("""
            INSERT INTO ai_pipeline_step (
                pipeline_run_id, step_key, step_order, status, retry_count,
                input_count, output_count, checkpoint_json, output_fingerprint,
                error_message, started_at, finished_at, created_at, updated_at
            ) VALUES (
                #{item.pipelineRunId}, #{item.stepKey}, #{item.stepOrder}, #{item.status},
                #{item.retryCount}, #{item.inputCount}, #{item.outputCount},
                #{item.checkpointJson}, #{item.outputFingerprint}, #{item.errorMessage},
                #{item.startedAt}, #{item.finishedAt}, #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiPipelineStep item);

    @Select("""
            SELECT * FROM ai_pipeline_step
            WHERE pipeline_run_id = #{pipelineRunId}
            ORDER BY step_order ASC
            FOR UPDATE
            """)
    List<AiPipelineStep> selectByRunIdForUpdate(@Param("pipelineRunId") Long pipelineRunId);

    @Update("""
            UPDATE ai_pipeline_step s
            INNER JOIN ai_pipeline_run r ON r.id = s.pipeline_run_id
            SET s.status = #{item.status}, s.retry_count = #{item.retryCount},
                s.input_count = #{item.inputCount}, s.output_count = #{item.outputCount},
                s.checkpoint_json = #{item.checkpointJson},
                s.output_fingerprint = #{item.outputFingerprint},
                s.error_message = #{item.errorMessage}, s.started_at = #{item.startedAt},
                s.finished_at = #{item.finishedAt}, s.updated_at = #{now}
            WHERE s.id = #{item.id}
              AND r.execution_owner = #{owner}
              AND r.lease_until IS NOT NULL
              AND r.lease_until >= #{now}
            """)
    int updateStateFenced(
            @Param("item") AiPipelineStep item,
            @Param("owner") String owner,
            @Param("now") LocalDateTime now
    );
}
