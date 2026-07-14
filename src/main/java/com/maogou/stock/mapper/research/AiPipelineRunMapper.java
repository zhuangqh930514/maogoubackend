package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AiPipelineRunMapper extends BaseMapper<AiPipelineRun> {

    @Insert("""
            INSERT INTO ai_pipeline_run (
                user_id, data_batch_id, strategy_release_id, model_version_id, trade_date,
                pipeline_type, idempotency_key, input_fingerprint, status, current_step,
                processed_count, success_count, failed_count, error_message, started_at,
                finished_at, created_at, updated_at
            ) VALUES (
                #{item.userId}, #{item.dataBatchId}, #{item.strategyReleaseId}, #{item.modelVersionId},
                #{item.tradeDate}, #{item.pipelineType}, #{item.idempotencyKey},
                #{item.inputFingerprint}, #{item.status}, #{item.currentStep},
                #{item.processedCount}, #{item.successCount}, #{item.failedCount},
                #{item.errorMessage}, #{item.startedAt}, #{item.finishedAt},
                #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiPipelineRun item);

    @Select("""
            SELECT * FROM ai_pipeline_run
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            FOR UPDATE
            """)
    AiPipelineRun selectByIdempotencyForUpdate(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Update("""
            UPDATE ai_pipeline_run
            SET execution_owner = #{owner}, lease_until = #{leaseUntil}, updated_at = #{now}
            WHERE id = #{id}
              AND status NOT IN ('SUCCESS', 'PARTIAL_SUCCESS')
              AND (execution_owner IS NULL OR lease_until IS NULL OR lease_until < #{now})
            """)
    int claimExecution(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_pipeline_run
            SET lease_until = #{leaseUntil}, updated_at = #{now}
            WHERE id = #{id}
              AND execution_owner = #{owner}
              AND lease_until IS NOT NULL
              AND lease_until >= #{now}
              AND status NOT IN ('SUCCESS', 'PARTIAL_SUCCESS')
            """)
    int renewExecution(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_pipeline_run
            SET status = #{item.status}, current_step = #{item.currentStep},
                processed_count = #{item.processedCount}, success_count = #{item.successCount},
                failed_count = #{item.failedCount}, error_message = #{item.errorMessage},
                started_at = #{item.startedAt}, finished_at = #{item.finishedAt}, updated_at = #{now}
            WHERE id = #{item.id}
              AND execution_owner = #{owner}
              AND lease_until IS NOT NULL
              AND lease_until >= #{now}
            """)
    int updateStateFenced(
            @Param("item") AiPipelineRun item,
            @Param("owner") String owner,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_pipeline_run
            SET execution_owner = NULL, lease_until = NULL, updated_at = #{now}
            WHERE id = #{id} AND execution_owner = #{owner}
            """)
    int releaseExecution(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("now") LocalDateTime now
    );
}
