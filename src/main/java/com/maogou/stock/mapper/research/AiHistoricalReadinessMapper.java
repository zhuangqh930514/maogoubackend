package com.maogou.stock.mapper.research;

import com.maogou.stock.domain.entity.research.AiHistoricalReadinessFeatureMetric;
import com.maogou.stock.domain.entity.research.AiHistoricalReadinessSummary;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only aggregate queries used by the historical readiness gate. */
public interface AiHistoricalReadinessMapper {

    @Select("""
            SELECT
              COUNT(DISTINCT s.trade_date) AS trading_days,
              COUNT(DISTINCT s.stock_code) AS stock_count,
              COUNT(l.id) AS tradability_eligible,
              SUM(CASE WHEN entry_state.id IS NOT NULL AND entry_batch.id IS NOT NULL
                       THEN 1 ELSE 0 END) AS tradability_ready,
              COUNT(l.id) AS universe_eligible,
              SUM(CASE WHEN EXISTS (
                    SELECT 1
                    FROM ai_research_universe_item ui
                    INNER JOIN ai_research_universe_snapshot us
                      ON us.id = ui.universe_snapshot_id
                    WHERE ui.id = s.universe_item_id
                      AND ui.included = 1
                      AND ui.listed_status = 'LISTED'
                      AND ui.effective_from <= s.trade_date
                      AND (ui.effective_to IS NULL OR ui.effective_to >= s.trade_date)
                      AND us.trade_date = s.trade_date
                      AND us.status = 'FINALIZED'
                      AND us.quality_status = 'READY'
                      AND us.point_in_time_status = 'READY'
                      AND (us.source_observed_at IS NULL OR us.source_observed_at <= #{asOfTime})
                  ) THEN 1 ELSE 0 END) AS universe_ready,
              COUNT(l.id) AS sector_eligible,
              SUM(CASE WHEN l.sector_excess_return IS NOT NULL
                            AND l.sector_membership_fingerprint IS NOT NULL
                            AND l.sector_membership_fingerprint <> ''
                       THEN 1 ELSE 0 END) AS sector_ready
            FROM ai_sample s
            INNER JOIN ai_data_batch sample_batch
              ON sample_batch.id = s.data_batch_id
             AND sample_batch.backfill_run_id = #{runId}
            INNER JOIN ai_sample_label l
              ON l.sample_id = s.id
             AND l.label_version = #{labelVersion}
             AND l.label_status = 'MATURED'
             AND l.execution_status = 'EXECUTED'
             AND l.fill_status = 'FILLED'
             AND l.is_current = 1
             AND l.label_available_at <= #{asOfTime}
             AND l.horizon_trading_days IN (1, 2, 3, 5)
            LEFT JOIN ai_security_daily_state entry_state
              ON entry_state.stock_code = l.stock_code
             AND entry_state.trade_date = l.entry_trade_date
             AND entry_state.is_current = 1
             AND entry_state.quality_status = 'READY'
             AND entry_state.buy_tradable = 1
            LEFT JOIN ai_data_batch entry_batch
              ON entry_batch.id = entry_state.source_batch_id
             AND entry_batch.backfill_run_id = #{runId}
            WHERE s.feature_version = #{featureVersion}
              AND s.quality_status = 'READY'
              AND s.tradable_status = 'TRADABLE'
              AND s.trade_date BETWEEN #{startDate} AND #{endDate}
              AND s.as_of_time <= #{asOfTime}
            """)
    AiHistoricalReadinessSummary selectSummary(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("labelVersion") String labelVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT l.horizon_trading_days AS dimension_key, COUNT(*) AS metric_count
            FROM ai_sample s
            INNER JOIN ai_data_batch sample_batch
              ON sample_batch.id = s.data_batch_id
             AND sample_batch.backfill_run_id = #{runId}
            INNER JOIN ai_sample_label l ON l.sample_id = s.id
            WHERE s.feature_version = #{featureVersion}
              AND s.quality_status = 'READY'
              AND s.tradable_status = 'TRADABLE'
              AND s.trade_date BETWEEN #{startDate} AND #{endDate}
              AND s.as_of_time <= #{asOfTime}
              AND l.label_version = #{labelVersion}
              AND l.label_status = 'MATURED'
              AND l.execution_status = 'EXECUTED'
              AND l.fill_status = 'FILLED'
              AND l.is_current = 1
              AND l.label_available_at <= #{asOfTime}
              AND l.horizon_trading_days IN (1, 2, 3, 5)
            GROUP BY l.horizon_trading_days
            ORDER BY l.horizon_trading_days
            """)
    List<DimensionMetric> selectHorizonCounts(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("labelVersion") String labelVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT s.market_regime AS dimension_key, COUNT(DISTINCT s.trade_date) AS metric_count
            FROM ai_sample s
            INNER JOIN ai_data_batch sample_batch
              ON sample_batch.id = s.data_batch_id
             AND sample_batch.backfill_run_id = #{runId}
            WHERE s.feature_version = #{featureVersion}
              AND s.quality_status = 'READY'
              AND s.tradable_status = 'TRADABLE'
              AND s.trade_date BETWEEN #{startDate} AND #{endDate}
              AND s.as_of_time <= #{asOfTime}
              AND s.market_regime IS NOT NULL
              AND s.market_regime <> ''
            GROUP BY s.market_regime
            """)
    List<DimensionMetric> selectRegimeDays(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT d.factor_code AS factor_code,
                   COUNT(s.id) AS total_count,
                   SUM(CASE WHEN v.id IS NOT NULL AND v.missing = 0
                                  AND v.normalized_value IS NOT NULL THEN 1 ELSE 0 END) AS ready_count,
                   SUM(CASE WHEN v.id IS NULL OR v.missing = 1
                                  OR v.normalized_value IS NULL THEN 1 ELSE 0 END) AS missing_count
            FROM ai_sample s
            INNER JOIN ai_data_batch sample_batch
              ON sample_batch.id = s.data_batch_id
             AND sample_batch.backfill_run_id = #{runId}
            CROSS JOIN ai_factor_definition d
            LEFT JOIN ai_factor_value v
              ON v.sample_id = s.id
             AND v.factor_definition_id = d.id
            WHERE s.feature_version = #{featureVersion}
              AND s.quality_status = 'READY'
              AND s.tradable_status = 'TRADABLE'
              AND d.factor_version = #{factorVersion}
              AND d.enabled = 1
              AND s.trade_date BETWEEN #{startDate} AND #{endDate}
              AND s.as_of_time <= #{asOfTime}
            GROUP BY d.factor_code
            ORDER BY d.factor_code
            """)
    List<AiHistoricalReadinessFeatureMetric> selectFeatureCoverage(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("factorVersion") String factorVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT l.actual_direction AS dimension_key, COUNT(*) AS metric_count
            FROM ai_sample s
            INNER JOIN ai_data_batch sample_batch
              ON sample_batch.id = s.data_batch_id
             AND sample_batch.backfill_run_id = #{runId}
            INNER JOIN ai_sample_label l ON l.sample_id = s.id
            WHERE s.feature_version = #{featureVersion}
              AND s.quality_status = 'READY'
              AND s.tradable_status = 'TRADABLE'
              AND s.trade_date BETWEEN #{startDate} AND #{endDate}
              AND s.as_of_time <= #{asOfTime}
              AND l.label_version = #{labelVersion}
              AND l.label_status = 'MATURED'
              AND l.execution_status = 'EXECUTED'
              AND l.fill_status = 'FILLED'
              AND l.is_current = 1
              AND l.label_available_at <= #{asOfTime}
              AND l.actual_direction IS NOT NULL
            GROUP BY l.actual_direction
            ORDER BY l.actual_direction
            """)
    List<DimensionMetric> selectClassDistribution(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("labelVersion") String labelVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_source_observation o
            INNER JOIN ai_data_batch b ON b.id = o.data_batch_id
            WHERE b.backfill_run_id = #{runId}
              AND EXISTS (
                    SELECT 1
                    FROM ai_sample s
                    WHERE s.data_batch_id = b.id
                      AND s.feature_version = #{featureVersion}
                      AND s.trade_date BETWEEN #{startDate} AND #{endDate}
                      AND s.as_of_time <= #{asOfTime}
                      AND o.available_at > s.as_of_time
              )
            """)
    int countPointInTimeViolations(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT COALESCE(SUM(duplicate_count - 1), 0)
            FROM (
                SELECT COUNT(*) AS duplicate_count
                FROM ai_sample_label l
                INNER JOIN ai_sample s ON s.id = l.sample_id
                INNER JOIN ai_data_batch b ON b.id = s.data_batch_id
                WHERE b.backfill_run_id = #{runId}
                  AND s.feature_version = #{featureVersion}
                  AND s.trade_date BETWEEN #{startDate} AND #{endDate}
                  AND l.label_version = #{labelVersion}
                  AND l.is_current = 1
                GROUP BY l.sample_id, l.horizon_trading_days, l.label_version
                HAVING COUNT(*) > 1
            ) duplicate_rows
            """)
    int countDuplicateLabels(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("labelVersion") String labelVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_source_observation o
            INNER JOIN ai_data_batch b ON b.id = o.data_batch_id
            WHERE b.backfill_run_id = #{runId}
              AND EXISTS (
                    SELECT 1
                    FROM ai_sample s
                    WHERE s.data_batch_id = b.id
                      AND s.feature_version = #{featureVersion}
                      AND s.trade_date BETWEEN #{startDate} AND #{endDate}
                      AND s.as_of_time <= #{asOfTime}
              )
              AND (
                   UPPER(COALESCE(o.provider_code, '')) IN ('MOCK', 'FIXTURE', 'LOCAL')
                   OR UPPER(COALESCE(o.source_type, '')) IN ('MOCK', 'FIXTURE', 'LOCAL')
                   OR UPPER(COALESCE(o.source_revision, '')) LIKE '%MOCK%'
              )
            """)
    int countMockSources(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_source_observation o
            INNER JOIN ai_data_batch b ON b.id = o.data_batch_id
            WHERE b.backfill_run_id = #{runId}
              AND EXISTS (
                    SELECT 1
                    FROM ai_sample s
                    WHERE s.data_batch_id = b.id
                      AND s.feature_version = #{featureVersion}
                      AND s.trade_date BETWEEN #{startDate} AND #{endDate}
                      AND s.as_of_time <= #{asOfTime}
              )
              AND UPPER(COALESCE(o.freshness_status, '')) = 'STALE'
            """)
    int countStaleSources(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_source_observation o
            INNER JOIN ai_data_batch b ON b.id = o.data_batch_id
            WHERE b.backfill_run_id = #{runId}
              AND EXISTS (
                    SELECT 1
                    FROM ai_sample s
                    WHERE s.data_batch_id = b.id
                      AND s.feature_version = #{featureVersion}
                      AND s.trade_date BETWEEN #{startDate} AND #{endDate}
                      AND s.as_of_time <= #{asOfTime}
              )
              AND (
                   UPPER(COALESCE(o.missing_reason, '')) LIKE '%INFER%'
                   OR UPPER(COALESCE(o.quality_status, '')) IN ('INFERRED', 'ASSUMED')
              )
            """)
    int countInferredFacts(
            @Param("runId") Long runId,
            @Param("featureVersion") String featureVersion,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    class DimensionMetric {
        public String dimensionKey;
        public Integer metricCount;
    }
}
