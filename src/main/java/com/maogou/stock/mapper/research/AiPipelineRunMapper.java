package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

public interface AiPipelineRunMapper extends BaseMapper<AiPipelineRun> {

    @Select("""
            SELECT * FROM ai_pipeline_run
            WHERE scope_type = 'GLOBAL'
              AND pipeline_type = 'GLOBAL_DAILY_RESEARCH'
              AND trade_date = #{tradeDate}
            ORDER BY id DESC
            LIMIT 1
            """)
    AiPipelineRun selectLatestGlobalDailyByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    @Select("""
            SELECT * FROM ai_pipeline_run
            WHERE scope_type = 'USER'
              AND pipeline_type = 'USER_DAILY_PROJECTION'
              AND owner_user_id = #{userId}
              AND trade_date = #{tradeDate}
            ORDER BY id DESC
            LIMIT 1
            """)
    AiPipelineRun selectLatestUserProjectionByTradeDate(
            @Param("userId") Long userId,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT * FROM ai_pipeline_run
            WHERE scope_type = 'GLOBAL'
              AND pipeline_type = 'GLOBAL_DAILY_RESEARCH'
              AND data_batch_id = #{dataBatchId}
            ORDER BY id DESC
            LIMIT 1
            """)
    AiPipelineRun selectLatestGlobalDailyByDataBatchId(@Param("dataBatchId") Long dataBatchId);

    @Select("""
            SELECT *
            FROM ai_pipeline_run
            WHERE scope_type = 'GLOBAL'
              AND pipeline_type = 'GLOBAL_DAILY_RESEARCH'
              AND status IN ('WAITING_SOURCE', 'FAILED_RECOVERABLE')
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= #{now}
            ORDER BY next_retry_at, id
            LIMIT #{limit}
            """)
    List<AiPipelineRun> selectDueGlobalDailyRuns(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Select("""
            SELECT *
            FROM ai_pipeline_run
            WHERE scope_type = 'USER'
              AND pipeline_type = 'USER_DAILY_PROJECTION'
              AND status = 'PARTIAL_SUCCESS'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= #{now}
            ORDER BY next_retry_at, id
            LIMIT #{limit}
            """)
    List<AiPipelineRun> selectDueUserProjectionRetries(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Select("""
            SELECT *
            FROM ai_pipeline_run
            WHERE status = 'RUNNING'
              AND finished_at IS NULL
              AND updated_at <= #{staleBefore}
              AND (lease_until IS NULL OR lease_until < #{now})
            ORDER BY updated_at, id
            LIMIT #{limit}
            """)
    List<AiPipelineRun> selectStaleRunning(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Select("""
            SELECT *
            FROM ai_pipeline_run
            WHERE status = 'FAILED_RECOVERABLE'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= #{now}
              AND pipeline_type NOT IN ('GLOBAL_DAILY_RESEARCH', 'USER_DAILY_PROJECTION')
            ORDER BY next_retry_at, id
            LIMIT #{limit}
            """)
    List<AiPipelineRun> selectRecoverableRunsRequiringManualRestart(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Insert("""
            INSERT INTO ai_pipeline_run (
                scope_type, owner_user_id, parent_run_id, data_batch_id, strategy_release_id,
                model_version_id, trade_date, pipeline_type, idempotency_key, input_fingerprint,
                status, execution_owner, lease_until, next_retry_at, current_step, retry_count,
                processed_count, success_count, failed_count, error_message, error_detail, started_at,
                finished_at, created_at, updated_at
            ) VALUES (
                #{item.scopeType}, #{item.ownerUserId}, #{item.parentRunId}, #{item.dataBatchId},
                #{item.strategyReleaseId}, #{item.modelVersionId}, #{item.tradeDate},
                #{item.pipelineType}, #{item.idempotencyKey}, #{item.inputFingerprint},
                #{item.status}, #{item.executionOwner}, #{item.leaseUntil}, #{item.nextRetryAt},
                #{item.currentStep}, #{item.retryCount},
                #{item.processedCount}, #{item.successCount}, #{item.failedCount},
                #{item.errorMessage}, #{item.errorDetail}, #{item.startedAt}, #{item.finishedAt},
                #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiPipelineRun item);

    @Select("""
            SELECT * FROM ai_pipeline_run
            WHERE idempotency_key = #{idempotencyKey}
            FOR UPDATE
            """)
    AiPipelineRun selectByIdempotencyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE ai_pipeline_run
            SET execution_owner = #{owner}, lease_until = #{leaseUntil}, updated_at = #{now}
            WHERE id = #{id}
              AND status NOT IN ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED_FINAL')
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
            SET data_batch_id = #{item.dataBatchId}, status = #{item.status},
                next_retry_at = #{item.nextRetryAt}, current_step = #{item.currentStep},
                retry_count = #{item.retryCount},
                processed_count = #{item.processedCount}, success_count = #{item.successCount},
                failed_count = #{item.failedCount}, error_message = #{item.errorMessage},
                error_detail = #{item.errorDetail},
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

    @Update("""
            UPDATE ai_pipeline_run
            SET status = 'FAILED_RECOVERABLE', execution_owner = NULL, lease_until = NULL,
                next_retry_at = #{now},
                error_message = #{message}, error_detail = #{detail},
                finished_at = #{now}, updated_at = #{now}
            WHERE id = #{id}
              AND status = 'RUNNING'
              AND finished_at IS NULL
              AND (lease_until IS NULL OR lease_until < #{now})
              AND updated_at <= #{staleBefore}
            """)
    int recoverStaleRunning(
            @Param("id") Long id,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now,
            @Param("message") String message,
            @Param("detail") String detail
    );

    @Update("""
            UPDATE ai_pipeline_run
            SET status = 'FAILED_FINAL', execution_owner = NULL, lease_until = NULL,
                next_retry_at = NULL, current_step = 'RECOVERY_MANUAL_REQUIRED',
                error_message = #{message}, error_detail = #{detail},
                finished_at = #{now}, updated_at = #{now}
            WHERE id = #{id}
              AND status = 'FAILED_RECOVERABLE'
              AND pipeline_type NOT IN ('GLOBAL_DAILY_RESEARCH', 'USER_DAILY_PROJECTION')
            """)
    int finalizeRecoverableRunRequiringManualRestart(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("message") String message,
            @Param("detail") String detail
    );

    @Update("""
            UPDATE ai_pipeline_run
            SET status = 'PENDING', execution_owner = NULL, lease_until = NULL,
                next_retry_at = NULL, current_step = 'RETRY_PENDING',
                retry_count = retry_count + 1, finished_at = NULL,
                error_message = #{message}, error_detail = #{detail}, updated_at = #{now}
            WHERE id = #{id}
              AND scope_type = 'USER'
              AND pipeline_type = 'USER_DAILY_PROJECTION'
              AND status = 'PARTIAL_SUCCESS'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= #{now}
              AND retry_count < #{maxAttempts}
            """)
    int resetDueUserProjectionRetry(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts,
            @Param("message") String message,
            @Param("detail") String detail
    );

    @Update("""
            UPDATE ai_pipeline_run
            SET next_retry_at = NULL, error_message = #{message}, error_detail = #{detail}, updated_at = #{now}
            WHERE id = #{id}
              AND scope_type = 'USER'
              AND pipeline_type = 'USER_DAILY_PROJECTION'
              AND status = 'PARTIAL_SUCCESS'
              AND next_retry_at IS NOT NULL
            """)
    int pauseUserProjectionRetry(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("message") String message,
            @Param("detail") String detail
    );
}
