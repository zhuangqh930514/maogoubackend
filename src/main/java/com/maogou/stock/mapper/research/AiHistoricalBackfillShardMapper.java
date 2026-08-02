package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillShard;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiHistoricalBackfillShardMapper extends BaseMapper<AiHistoricalBackfillShard> {

    @Select("SELECT * FROM ai_historical_backfill_shard WHERE id = #{id} LIMIT 1")
    AiHistoricalBackfillShard selectByShardId(@Param("id") Long id);

    @Select("SELECT * FROM ai_historical_backfill_shard WHERE idempotency_key = #{key} LIMIT 1")
    AiHistoricalBackfillShard selectByIdempotencyKey(@Param("key") String key);

    @Select("""
            SELECT COUNT(*)
            FROM ai_historical_backfill_shard
            WHERE backfill_run_id = #{runId}
              AND (#{stageKey} IS NULL OR stage_key = #{stageKey})
              AND (#{status} IS NULL OR status = #{status})
              AND (#{tradeDate} IS NULL OR trade_date = #{tradeDate})
            """)
    long countByRun(
            @Param("runId") Long runId,
            @Param("stageKey") String stageKey,
            @Param("status") String status,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT *
            FROM ai_historical_backfill_shard
            WHERE backfill_run_id = #{runId}
              AND (#{stageKey} IS NULL OR stage_key = #{stageKey})
              AND (#{status} IS NULL OR status = #{status})
              AND (#{tradeDate} IS NULL OR trade_date = #{tradeDate})
            ORDER BY CASE stage_key
                         WHEN 'IMPORT_HISTORICAL_EVIDENCE' THEN 1
                         WHEN 'REPLAY_BLOCK' THEN 2
                         WHEN 'MATURE_HISTORICAL_SAMPLE_LABELS' THEN 3
                         WHEN 'EVALUATE_HISTORICAL_PREDICTIONS' THEN 4
                         WHEN 'READINESS_CHECK' THEN 5
                         ELSE 99
                     END,
                     bucket_no, trade_date, id
            LIMIT #{offset}, #{pageSize}
            """)
    List<AiHistoricalBackfillShard> selectPageByRun(
            @Param("runId") Long runId,
            @Param("stageKey") String stageKey,
            @Param("status") String status,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Insert("""
            INSERT INTO ai_historical_backfill_shard (
                backfill_run_id, stage_key, trade_date, bucket_no, idempotency_key,
                status, attempt_no, max_attempts, input_count, output_count, rejected_count,
                checkpoint_json, input_fingerprint, output_fingerprint, provider_code,
                endpoint_type, next_retry_at, lease_owner, lease_until, started_at,
                finished_at, error_code, error_message, error_detail, created_at, updated_at
            ) VALUES (
                #{item.backfillRunId}, #{item.stageKey}, #{item.tradeDate}, #{item.bucketNo},
                #{item.idempotencyKey}, #{item.status}, #{item.attemptNo}, #{item.maxAttempts},
                #{item.inputCount}, #{item.outputCount}, #{item.rejectedCount},
                #{item.checkpointJson}, #{item.inputFingerprint}, #{item.outputFingerprint},
                #{item.providerCode}, #{item.endpointType}, #{item.nextRetryAt},
                #{item.leaseOwner}, #{item.leaseUntil}, #{item.startedAt}, #{item.finishedAt},
                #{item.errorCode}, #{item.errorMessage}, #{item.errorDetail},
                #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiHistoricalBackfillShard item);

    @Update("""
            UPDATE ai_historical_backfill_shard s
            INNER JOIN ai_historical_backfill_run r ON r.id = s.backfill_run_id
            SET s.status = 'RUNNING', s.attempt_no = s.attempt_no + 1,
                s.lease_owner = #{owner}, s.lease_until = #{leaseUntil},
                s.started_at = COALESCE(s.started_at, #{now}), s.finished_at = NULL,
                s.error_code = NULL, s.error_message = NULL, s.error_detail = NULL,
                s.updated_at = #{now}
            WHERE s.id = #{id}
              AND r.status = 'RUNNING'
              AND s.status IN ('PENDING', 'FAILED_RETRYABLE')
              AND (s.next_retry_at IS NULL OR s.next_retry_at <= #{now})
              AND (s.lease_until IS NULL OR s.lease_until < #{now})
            """)
    int claimLease(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_historical_backfill_shard
            SET lease_until = #{leaseUntil}, updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING' AND lease_owner = #{owner}
              AND lease_until IS NOT NULL AND lease_until >= #{now}
            """)
    int renewLease(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_historical_backfill_shard
            SET status = #{item.status}, output_count = #{item.outputCount},
                rejected_count = #{item.rejectedCount}, checkpoint_json = #{item.checkpointJson},
                output_fingerprint = #{item.outputFingerprint}, provider_code = #{item.providerCode},
                endpoint_type = #{item.endpointType}, next_retry_at = #{item.nextRetryAt},
                finished_at = #{item.finishedAt}, error_code = #{item.errorCode},
                error_message = #{item.errorMessage}, error_detail = #{item.errorDetail},
                lease_until = #{leaseUntil}, updated_at = #{now}
            WHERE id = #{item.id} AND status = 'RUNNING' AND lease_owner = #{owner}
              AND lease_until IS NOT NULL AND lease_until >= #{now}
            """)
    int updateStateFenced(
            @Param("item") AiHistoricalBackfillShard item,
            @Param("owner") String owner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_historical_backfill_shard
            SET status = 'PENDING', lease_owner = NULL, lease_until = NULL,
                next_retry_at = NULL, error_message = #{reason}, updated_at = #{now}
            WHERE backfill_run_id = #{runId}
              AND status IN ('FAILED', 'FAILED_RECOVERABLE', 'FAILED_RETRYABLE',
                             'BLOCKED_BY_QUALITY', 'INSUFFICIENT_DATA')
            """)
    int retryFailedByRun(
            @Param("runId") Long runId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_historical_backfill_shard
            SET status = 'FAILED_RETRYABLE', lease_owner = NULL, lease_until = NULL,
                next_retry_at = #{nextRetryAt}, error_code = #{errorCode},
                error_message = #{errorMessage}, error_detail = #{errorDetail},
                finished_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING'
              AND (lease_until IS NULL OR lease_until < #{now})
            """)
    int recoverExpiredLease(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("errorDetail") String errorDetail
    );
}
