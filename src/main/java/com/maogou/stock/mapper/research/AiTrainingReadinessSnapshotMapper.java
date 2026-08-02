package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiTrainingReadinessSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiTrainingReadinessSnapshotMapper extends BaseMapper<AiTrainingReadinessSnapshot> {

    @Select("SELECT * FROM ai_training_readiness_snapshot WHERE id = #{id} LIMIT 1")
    AiTrainingReadinessSnapshot selectBySnapshotId(@Param("id") Long id);

    @Select("""
            SELECT * FROM ai_training_readiness_snapshot
            WHERE backfill_run_id = #{runId}
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    AiTrainingReadinessSnapshot selectLatestByRunId(@Param("runId") Long runId);

    @Select("""
            SELECT * FROM ai_training_readiness_snapshot
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    AiTrainingReadinessSnapshot selectLatest();

    @Insert("""
            INSERT INTO ai_training_readiness_snapshot (
                backfill_run_id, pipeline_run_id, as_of_time, feature_version, factor_version,
                label_version, calendar_version, trading_days, stock_count, horizon_counts_json,
                regime_days_json, tradability_eligible, tradability_ready, tradability_coverage,
                universe_eligible, universe_ready, universe_coverage, sector_eligible,
                sector_ready, sector_coverage, feature_coverage_json, class_distribution_json,
                leakage_violation_count, duplicate_count, mock_source_count, stale_source_count,
                inferred_fact_count, status, blocking_gaps_json, evidence_checksum, created_at
            ) VALUES (
                #{item.backfillRunId}, #{item.pipelineRunId}, #{item.asOfTime}, #{item.featureVersion},
                #{item.factorVersion}, #{item.labelVersion}, #{item.calendarVersion},
                #{item.tradingDays}, #{item.stockCount}, #{item.horizonCountsJson},
                #{item.regimeDaysJson}, #{item.tradabilityEligible}, #{item.tradabilityReady},
                #{item.tradabilityCoverage}, #{item.universeEligible}, #{item.universeReady},
                #{item.universeCoverage}, #{item.sectorEligible}, #{item.sectorReady},
                #{item.sectorCoverage}, #{item.featureCoverageJson}, #{item.classDistributionJson},
                #{item.leakageViolationCount}, #{item.duplicateCount}, #{item.mockSourceCount},
                #{item.staleSourceCount}, #{item.inferredFactCount}, #{item.status},
                #{item.blockingGapsJson}, #{item.evidenceChecksum}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiTrainingReadinessSnapshot item);
}
