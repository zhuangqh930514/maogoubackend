package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence boundary for the historical fast-start coordinator.
 *
 * <p>Lease and state writes are fenced by the owner token. The coordinator
 * must never update a run using a stale in-memory object alone.</p>
 */
public interface AiHistoricalBackfillRunMapper extends BaseMapper<AiHistoricalBackfillRun> {

    @Select("SELECT * FROM ai_historical_backfill_run WHERE id = #{id} LIMIT 1")
    AiHistoricalBackfillRun selectByRunId(@Param("id") Long id);

    @Select("SELECT * FROM ai_historical_backfill_run WHERE run_key = #{runKey} LIMIT 1")
    AiHistoricalBackfillRun selectByRunKey(@Param("runKey") String runKey);

    @Select("""
            SELECT COUNT(*)
            FROM ai_historical_backfill_run
            WHERE (#{status} IS NULL OR status = #{status})
            """)
    long countByStatus(@Param("status") String status);

    @Select("""
            SELECT *
            FROM ai_historical_backfill_run
            WHERE (#{status} IS NULL OR status = #{status})
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<AiHistoricalBackfillRun> selectPage(
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Insert("""
            INSERT INTO ai_historical_backfill_run (
                pipeline_run_id, run_key, mode, requested_start_date, requested_end_date,
                effective_sample_start_date, effective_sample_end_date, target_trading_days,
                target_stocks_per_day, feature_version, factor_version, label_version,
                calendar_version, industry_standard, source_manifest_checksum, run_config_json,
                status, current_stage, total_shards, succeeded_shards, quarantined_shards,
                failed_shards, readiness_snapshot_id, lease_owner, lease_until,
                last_heartbeat_at, error_summary, requested_by, created_at, started_at,
                finished_at, updated_at
            ) VALUES (
                #{item.pipelineRunId}, #{item.runKey}, #{item.mode}, #{item.requestedStartDate},
                #{item.requestedEndDate}, #{item.effectiveSampleStartDate},
                #{item.effectiveSampleEndDate}, #{item.targetTradingDays},
                #{item.targetStocksPerDay}, #{item.featureVersion}, #{item.factorVersion},
                #{item.labelVersion}, #{item.calendarVersion}, #{item.industryStandard},
                #{item.sourceManifestChecksum}, #{item.runConfigJson}, #{item.status},
                #{item.currentStage}, #{item.totalShards}, #{item.succeededShards},
                #{item.quarantinedShards}, #{item.failedShards}, #{item.readinessSnapshotId},
                #{item.leaseOwner}, #{item.leaseUntil}, #{item.lastHeartbeatAt},
                #{item.errorSummary}, #{item.requestedBy}, #{item.createdAt}, #{item.startedAt},
                #{item.finishedAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiHistoricalBackfillRun item);

    @Update("""
            UPDATE ai_historical_backfill_run
            SET status = 'RUNNING', lease_owner = #{owner}, lease_until = #{leaseUntil},
                last_heartbeat_at = #{now}, started_at = COALESCE(started_at, #{now}),
                updated_at = #{now}
            WHERE id = #{id}
              AND status IN ('PLANNED', 'FAILED_RETRYABLE')
              AND (lease_until IS NULL OR lease_until < #{now})
            """)
    int claimLease(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_historical_backfill_run
            SET lease_until = #{leaseUntil}, last_heartbeat_at = #{now}, updated_at = #{now}
            WHERE id = #{id}
              AND status = 'RUNNING'
              AND lease_owner = #{owner}
              AND lease_until IS NOT NULL
              AND lease_until >= #{now}
            """)
    int renewLease(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_historical_backfill_run
            SET pipeline_run_id = #{item.pipelineRunId}, status = #{item.status},
                current_stage = #{item.currentStage}, total_shards = #{item.totalShards},
                succeeded_shards = #{item.succeededShards}, quarantined_shards = #{item.quarantinedShards},
                failed_shards = #{item.failedShards}, readiness_snapshot_id = #{item.readinessSnapshotId},
                error_summary = #{item.errorSummary}, started_at = #{item.startedAt},
                finished_at = #{item.finishedAt}, lease_until = #{leaseUntil},
                last_heartbeat_at = #{now}, updated_at = #{now}
            WHERE id = #{item.id}
              AND status = 'RUNNING'
              AND lease_owner = #{owner}
              AND lease_until IS NOT NULL
              AND lease_until >= #{now}
            """)
    int updateStateFenced(
            @Param("item") AiHistoricalBackfillRun item,
            @Param("owner") String owner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_historical_backfill_run
            SET status = 'PAUSED', lease_owner = NULL, lease_until = NULL,
                error_summary = #{reason}, updated_at = #{now}
            WHERE id = #{id}
              AND status IN ('PLANNED', 'RUNNING', 'FAILED_RETRYABLE')
            """)
    int pause(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_historical_backfill_run
            SET status = 'PLANNED', lease_owner = NULL, lease_until = NULL,
                error_summary = NULL, finished_at = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'PAUSED'
            """)
    int resume(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_historical_backfill_run
            SET status = 'CANCELLED', lease_owner = NULL, lease_until = NULL,
                finished_at = #{now}, error_summary = #{reason}, updated_at = #{now}
            WHERE id = #{id}
              AND status NOT IN ('SUCCESS', 'CANCELLED')
            """)
    int cancel(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_historical_backfill_run
            SET status = 'PLANNED', lease_owner = NULL, lease_until = NULL,
                finished_at = NULL, error_summary = #{reason}, updated_at = #{now}
            WHERE id = #{id}
              AND status IN ('FAILED', 'FAILED_FINAL', 'FAILED_RETRYABLE', 'PARTIAL_SUCCESS',
                             'BLOCKED_BY_QUALITY', 'INSUFFICIENT_DATA', 'QUARANTINED')
            """)
    int retryFailed(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_historical_backfill_run
            SET status = 'FAILED_RETRYABLE', lease_owner = NULL, lease_until = NULL,
                finished_at = #{now}, error_summary = #{message}, updated_at = #{now}
            WHERE id = #{id}
              AND status = 'RUNNING'
              AND (lease_until IS NULL OR lease_until < #{now})
            """)
    int recoverExpiredLease(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("message") String message
    );

    @Update("""
            UPDATE ai_historical_backfill_run
            SET lease_owner = NULL, lease_until = NULL, updated_at = #{now}
            WHERE id = #{id} AND lease_owner = #{owner}
            """)
    int releaseLease(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_historical_backfill_run
            SET readiness_snapshot_id = #{snapshotId}, updated_at = #{now}
            WHERE id = #{runId}
            """)
    int attachReadinessSnapshot(
            @Param("runId") Long runId,
            @Param("snapshotId") Long snapshotId,
            @Param("now") LocalDateTime now
    );
}
