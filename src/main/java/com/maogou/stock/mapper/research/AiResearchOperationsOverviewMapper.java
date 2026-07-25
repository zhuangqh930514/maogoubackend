package com.maogou.stock.mapper.research;

import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Bounded, aggregate-first queries for the operator dashboard. Keep user-facing report queries
 * out of this mapper: this endpoint is intentionally limited to operators.
 */
public interface AiResearchOperationsOverviewMapper {

    @Select("""
            SELECT *
            FROM ai_pipeline_run
            WHERE scope_type = 'GLOBAL'
              AND pipeline_type = 'GLOBAL_DAILY_RESEARCH'
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    AiPipelineRun selectLatestGlobalRun();

    @Select("""
            SELECT status, COUNT(*) AS record_count
            FROM ai_pipeline_run
            WHERE created_at >= #{since}
            GROUP BY status
            """)
    List<StatusCountRow> selectRunStatusCounts(@Param("since") LocalDateTime since);

    @Select("""
            SELECT id, started_at, finished_at
            FROM ai_pipeline_run
            WHERE created_at >= #{since}
              AND started_at IS NOT NULL
              AND finished_at IS NOT NULL
            ORDER BY finished_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<AiPipelineRun> selectCompletedRuns(
            @Param("since") LocalDateTime since,
            @Param("limit") int limit
    );

    @Select("""
            SELECT *
            FROM ai_pipeline_run
            WHERE created_at >= #{since}
              AND (
                    status IN ('WAITING_SOURCE', 'FAILED_RECOVERABLE', 'FAILED_FINAL', 'FAILED')
                    OR failed_count > 0
              )
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<AiPipelineRun> selectAttentionRuns(
            @Param("since") LocalDateTime since,
            @Param("limit") int limit
    );

    @Select("""
            SELECT quality_status AS status, COUNT(*) AS record_count
            FROM ai_sample
            WHERE data_batch_id = #{dataBatchId}
            GROUP BY quality_status
            """)
    List<StatusCountRow> selectSampleCoverage(@Param("dataBatchId") Long dataBatchId);

    @Select("""
            SELECT *
            FROM ai_analysis_report
            WHERE generated_at >= #{since}
              AND status IN ('FAILED', 'FAILED_FINAL')
            ORDER BY generated_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<AiAnalysisReport> selectRecentModelFailures(
            @Param("since") LocalDateTime since,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_analysis_report
            WHERE generated_at >= #{since}
              AND status IN ('FAILED', 'FAILED_FINAL')
            """)
    long selectModelFailureCount(@Param("since") LocalDateTime since);

    @Select("""
            SELECT COUNT(*)
            FROM user_account u
            WHERE u.status = 'ACTIVE'
              AND COALESCE(u.deleted, 0) = 0
              AND (
                    EXISTS (
                        SELECT 1 FROM watch_stock w
                        WHERE w.user_id = u.id AND COALESCE(w.deleted, 0) = 0
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM trade_record t
                        WHERE t.user_id = u.id AND COALESCE(t.deleted, 0) = 0
                        GROUP BY t.user_id
                        HAVING SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) > 0
                    )
              )
            """)
    long selectEligibleUserCount();

    @Select("""
            SELECT COUNT(*)
            FROM user_account u
            WHERE u.status = 'ACTIVE'
              AND COALESCE(u.deleted, 0) = 0
              AND (
                    EXISTS (
                        SELECT 1 FROM watch_stock w
                        WHERE w.user_id = u.id AND COALESCE(w.deleted, 0) = 0
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM trade_record t
                        WHERE t.user_id = u.id AND COALESCE(t.deleted, 0) = 0
                        GROUP BY t.user_id
                        HAVING SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) > 0
                    )
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM ai_research_daily_report r
                    WHERE r.user_id = u.id
                      AND r.trade_date = #{tradeDate}
                      AND r.is_current = 1
              )
            """)
    long selectMissingDailyReportUserCount(@Param("tradeDate") LocalDate tradeDate);

    @Select("""
            SELECT u.id AS user_id,
                   u.display_name AS display_name,
                   CASE WHEN EXISTS (
                        SELECT 1 FROM watch_stock w
                        WHERE w.user_id = u.id AND COALESCE(w.deleted, 0) = 0
                   ) THEN 1 ELSE 0 END AS has_watchlist,
                   CASE WHEN EXISTS (
                        SELECT 1
                        FROM trade_record t
                        WHERE t.user_id = u.id AND COALESCE(t.deleted, 0) = 0
                        GROUP BY t.user_id
                        HAVING SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) > 0
                   ) THEN 1 ELSE 0 END AS has_holding
            FROM user_account u
            WHERE u.status = 'ACTIVE'
              AND COALESCE(u.deleted, 0) = 0
              AND (
                    EXISTS (
                        SELECT 1 FROM watch_stock w
                        WHERE w.user_id = u.id AND COALESCE(w.deleted, 0) = 0
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM trade_record t
                        WHERE t.user_id = u.id AND COALESCE(t.deleted, 0) = 0
                        GROUP BY t.user_id
                        HAVING SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) > 0
                    )
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM ai_research_daily_report r
                    WHERE r.user_id = u.id
                      AND r.trade_date = #{tradeDate}
                      AND r.is_current = 1
              )
            ORDER BY u.id
            LIMIT #{limit}
            """)
    List<UserReportGapRow> selectUsersMissingDailyReport(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("limit") int limit
    );

    @Select("""
            WITH latest_global_days AS (
                SELECT trade_date
                FROM ai_pipeline_run
                WHERE scope_type = 'GLOBAL'
                  AND pipeline_type = 'GLOBAL_DAILY_RESEARCH'
                  AND status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                GROUP BY trade_date
                ORDER BY trade_date DESC
                LIMIT 2
            )
            SELECT u.id AS user_id,
                   u.display_name AS display_name,
                   GROUP_CONCAT(DATE_FORMAT(day.trade_date, '%Y-%m-%d')
                       ORDER BY day.trade_date DESC SEPARATOR ',') AS missing_trade_dates
            FROM user_account u
            CROSS JOIN latest_global_days day
            LEFT JOIN ai_research_daily_report report
                ON report.user_id = u.id
               AND report.trade_date = day.trade_date
               AND report.is_current = 1
               AND report.report_status = 'READY'
            WHERE u.status = 'ACTIVE'
              AND COALESCE(u.deleted, 0) = 0
              AND (
                    EXISTS (
                        SELECT 1 FROM watch_stock w
                        WHERE w.user_id = u.id AND COALESCE(w.deleted, 0) = 0
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM trade_record t
                        WHERE t.user_id = u.id AND COALESCE(t.deleted, 0) = 0
                        GROUP BY t.user_id
                        HAVING SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) > 0
                    )
              )
              AND report.id IS NULL
            GROUP BY u.id, u.display_name
            HAVING COUNT(DISTINCT day.trade_date) = 2
            ORDER BY u.id
            LIMIT #{limit}
            """)
    List<ConsecutiveReportGapRow> selectUsersMissingTwoLatestDailyReports(@Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM (
                SELECT t.user_id, t.stock_code
                FROM trade_record t
                INNER JOIN user_account u ON u.id = t.user_id
                WHERE t.deleted = 0
                  AND u.status = 'ACTIVE'
                  AND COALESCE(u.deleted, 0) = 0
                GROUP BY t.user_id, t.stock_code
                HAVING SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) > 0
            ) positions
            """)
    long selectActiveHoldingCount();

    @Select("""
            SELECT COUNT(*)
            FROM (
                SELECT t.user_id, t.stock_code
                FROM trade_record t
                INNER JOIN user_account u ON u.id = t.user_id
                WHERE t.deleted = 0
                  AND u.status = 'ACTIVE'
                  AND COALESCE(u.deleted, 0) = 0
                GROUP BY t.user_id, t.stock_code
                HAVING SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) > 0
                   AND NOT EXISTS (
                       SELECT 1
                       FROM ai_daily_decision_item d
                       WHERE d.user_id = t.user_id
                         AND d.stock_code = t.stock_code
                         AND d.trade_date = #{tradeDate}
                   )
            ) positions
            """)
    long selectHoldingWithoutDailyConclusionCount(@Param("tradeDate") LocalDate tradeDate);

    @Select("""
            SELECT t.user_id AS user_id,
                   t.stock_code AS stock_code,
                   MAX(t.stock_name) AS stock_name,
                   SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) AS net_quantity
            FROM trade_record t
            INNER JOIN user_account u ON u.id = t.user_id
            WHERE t.deleted = 0
              AND u.status = 'ACTIVE'
              AND COALESCE(u.deleted, 0) = 0
            GROUP BY t.user_id, t.stock_code
            HAVING SUM(CASE WHEN t.side = 'SELL' THEN -t.quantity ELSE t.quantity END) > 0
               AND NOT EXISTS (
                   SELECT 1
                   FROM ai_daily_decision_item d
                   WHERE d.user_id = t.user_id
                     AND d.stock_code = t.stock_code
                     AND d.trade_date = #{tradeDate}
               )
            ORDER BY t.user_id, t.stock_code
            LIMIT #{limit}
            """)
    List<HoldingGapRow> selectHoldingsWithoutDailyConclusion(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_daily_decision_item d
            INNER JOIN ai_daily_decision_snapshot s
                ON s.id = d.decision_snapshot_id
               AND s.user_id = d.user_id
               AND s.is_current = 1
            INNER JOIN ai_analysis_report r
                ON r.id = d.report_id AND r.user_id = d.user_id
            WHERE d.trade_date = #{tradeDate}
              AND d.report_id IS NOT NULL
              AND d.final_action IS NOT NULL
              AND r.final_action IS NOT NULL
              AND r.status = 'SUCCESS'
              AND d.final_action <> r.final_action
            """)
    long selectDecisionConflictCount(@Param("tradeDate") LocalDate tradeDate);

    @Select("""
            SELECT d.user_id AS user_id,
                   d.id AS decision_item_id,
                   d.report_id AS report_id,
                   d.stock_code AS stock_code,
                   d.stock_name AS stock_name,
                   d.final_action AS decision_action,
                   r.final_action AS report_action
            FROM ai_daily_decision_item d
            INNER JOIN ai_daily_decision_snapshot s
                ON s.id = d.decision_snapshot_id
               AND s.user_id = d.user_id
               AND s.is_current = 1
            INNER JOIN ai_analysis_report r
                ON r.id = d.report_id AND r.user_id = d.user_id
            WHERE d.trade_date = #{tradeDate}
              AND d.report_id IS NOT NULL
              AND d.final_action IS NOT NULL
              AND r.final_action IS NOT NULL
              AND r.status = 'SUCCESS'
              AND d.final_action <> r.final_action
            ORDER BY d.user_id, d.stock_code, d.id
            LIMIT #{limit}
            """)
    List<DecisionConflictRow> selectDecisionConflicts(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_daily_decision_item item
            INNER JOIN ai_daily_decision_snapshot snapshot
                ON snapshot.id = item.decision_snapshot_id
               AND snapshot.user_id = item.user_id
               AND snapshot.is_current = 1
            WHERE item.trade_date = #{tradeDate}
              AND item.report_id IS NULL
            """)
    long selectDailyDecisionWithoutReportCount(@Param("tradeDate") LocalDate tradeDate);

    @Select("""
            SELECT COUNT(DISTINCT u.id)
            FROM ai_research_universe_item u
            LEFT JOIN ai_sample s
                ON s.universe_item_id = u.id AND s.data_batch_id = #{dataBatchId}
            WHERE u.universe_snapshot_id = #{universeSnapshotId}
              AND u.included = 1
              AND (
                    UPPER(COALESCE(u.stock_name, '')) LIKE '*ST%'
                    OR UPPER(COALESCE(u.stock_name, '')) LIKE 'ST%'
                    OR UPPER(COALESCE(u.stock_name, '')) LIKE 'PT%'
                    OR COALESCE(u.stock_name, '') LIKE '%退%'
                    OR COALESCE(u.listed_status, '') <> 'LISTED'
                    OR (COALESCE(s.quality_status, '') = 'READY'
                        AND COALESCE(s.tradable_status, '') <> 'TRADABLE')
              )
            """)
    long selectUniversePollutionCount(
            @Param("universeSnapshotId") Long universeSnapshotId,
            @Param("dataBatchId") Long dataBatchId
    );

    @Select("""
            SELECT u.id AS universe_item_id,
                   u.stock_code AS stock_code,
                   u.stock_name AS stock_name,
                   u.source_type AS source_type,
                   u.listed_status AS listed_status,
                   s.quality_status AS quality_status,
                   s.tradable_status AS tradable_status,
                   CASE
                       WHEN UPPER(COALESCE(u.stock_name, '')) LIKE '*ST%'
                            OR UPPER(COALESCE(u.stock_name, '')) LIKE 'ST%' THEN 'ST_SECURITY'
                       WHEN UPPER(COALESCE(u.stock_name, '')) LIKE 'PT%' THEN 'PT_SECURITY'
                       WHEN COALESCE(u.stock_name, '') LIKE '%退%' THEN 'DELISTING_NAME'
                       WHEN COALESCE(u.listed_status, '') <> 'LISTED' THEN 'NON_LISTED_STATUS'
                       ELSE 'NON_TRADABLE'
                   END AS issue_type,
                   CASE
                       WHEN UPPER(COALESCE(u.stock_name, '')) LIKE '*ST%'
                            OR UPPER(COALESCE(u.stock_name, '')) LIKE 'ST%' THEN '名称命中 ST 风险标识'
                       WHEN UPPER(COALESCE(u.stock_name, '')) LIKE 'PT%' THEN '名称命中 PT 风险标识'
                       WHEN COALESCE(u.stock_name, '') LIKE '%退%' THEN '名称包含退市标识'
                       WHEN COALESCE(u.listed_status, '') <> 'LISTED' THEN '上市状态不是 LISTED'
                       ELSE '样本不可交易'
                   END AS cause
            FROM ai_research_universe_item u
            LEFT JOIN ai_sample s
                ON s.universe_item_id = u.id AND s.data_batch_id = #{dataBatchId}
            WHERE u.universe_snapshot_id = #{universeSnapshotId}
              AND u.included = 1
              AND (
                    UPPER(COALESCE(u.stock_name, '')) LIKE '*ST%'
                    OR UPPER(COALESCE(u.stock_name, '')) LIKE 'ST%'
                    OR UPPER(COALESCE(u.stock_name, '')) LIKE 'PT%'
                    OR COALESCE(u.stock_name, '') LIKE '%退%'
                    OR COALESCE(u.listed_status, '') <> 'LISTED'
                    OR (COALESCE(s.quality_status, '') = 'READY'
                        AND COALESCE(s.tradable_status, '') <> 'TRADABLE')
              )
            ORDER BY u.stock_code, u.id
            LIMIT #{limit}
            """)
    List<UniversePollutionRow> selectUniversePollutionItems(
            @Param("universeSnapshotId") Long universeSnapshotId,
            @Param("dataBatchId") Long dataBatchId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_research_universe_item_lineage lineage
            INNER JOIN ai_research_universe_item item ON item.id = lineage.universe_item_id
            WHERE item.universe_snapshot_id = #{universeSnapshotId}
            """)
    long selectUniverseLineageCount(@Param("universeSnapshotId") Long universeSnapshotId);

    @Select("""
            SELECT item.id AS universe_item_id,
                   item.stock_code AS stock_code,
                   item.stock_name AS stock_name,
                   lineage.source_type AS source_type,
                   lineage.owner_user_id AS owner_user_id,
                   lineage.source_record_id AS source_record_id,
                   lineage.active_at_snapshot AS active_at_snapshot,
                   '快照来源记录未标记为当时有效，禁止作为正式用户兴趣来源' AS cause
            FROM ai_research_universe_item_lineage lineage
            INNER JOIN ai_research_universe_item item ON item.id = lineage.universe_item_id
            WHERE item.universe_snapshot_id = #{universeSnapshotId}
              AND COALESCE(lineage.active_at_snapshot, 0) <> 1
            ORDER BY item.stock_code, lineage.source_type, lineage.owner_user_id, lineage.source_record_id
            LIMIT #{limit}
            """)
    List<UniverseLineageRow> selectInvalidUniverseLineages(
            @Param("universeSnapshotId") Long universeSnapshotId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
            FROM ai_research_universe_item_lineage lineage
            INNER JOIN ai_research_universe_item item ON item.id = lineage.universe_item_id
            WHERE item.universe_snapshot_id = #{universeSnapshotId}
              AND COALESCE(lineage.active_at_snapshot, 0) <> 1
            """)
    long selectInvalidUniverseLineageCount(@Param("universeSnapshotId") Long universeSnapshotId);

    class StatusCountRow {
        public String status;
        public Long recordCount;
    }

    class UserReportGapRow {
        public Long userId;
        public String displayName;
        public Integer hasWatchlist;
        public Integer hasHolding;
    }

    class ConsecutiveReportGapRow {
        public Long userId;
        public String displayName;
        public String missingTradeDates;
    }

    class HoldingGapRow {
        public Long userId;
        public String stockCode;
        public String stockName;
        public Long netQuantity;
    }

    class DecisionConflictRow {
        public Long userId;
        public Long decisionItemId;
        public Long reportId;
        public String stockCode;
        public String stockName;
        public String decisionAction;
        public String reportAction;
    }

    class UniversePollutionRow {
        public Long universeItemId;
        public String stockCode;
        public String stockName;
        public String sourceType;
        public String listedStatus;
        public String qualityStatus;
        public String tradableStatus;
        public String issueType;
        public String cause;
    }

    class UniverseLineageRow {
        public Long universeItemId;
        public String stockCode;
        public String stockName;
        public String sourceType;
        public Long ownerUserId;
        public Long sourceRecordId;
        public Integer activeAtSnapshot;
        public String cause;
    }
}
