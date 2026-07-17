package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetItem;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetSource;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetSourceQuery;
import com.maogou.stock.domain.entity.research.AiTrainingReadinessMetric;
import com.maogou.stock.domain.entity.research.AiTrainingSourceSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AiTrainingDatasetItemMapper extends BaseMapper<AiTrainingDatasetItem> {

    @Select("""
            SELECT CONVERT('TRADING_DAYS' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS dimension_type,
                   CONVERT('ALL' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS dimension_key,
                   COUNT(DISTINCT trade_date) AS metric_count
            FROM ai_sample
            WHERE quality_status = 'READY' AND tradable_status = 'TRADABLE'
              AND as_of_time <= #{asOfTime}
            UNION ALL
            SELECT CONVERT('STOCKS' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS dimension_type,
                   CONVERT('ALL' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS dimension_key,
                   COUNT(DISTINCT stock_code) AS metric_count
            FROM ai_sample
            WHERE quality_status = 'READY' AND tradable_status = 'TRADABLE'
              AND as_of_time <= #{asOfTime}
            UNION ALL
            SELECT CONVERT('HORIZON' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS dimension_type,
                   CONVERT(CAST(l.horizon_trading_days AS CHAR) USING utf8mb4)
                       COLLATE utf8mb4_unicode_ci AS dimension_key,
                   COUNT(*) AS metric_count
            FROM ai_sample_label l
            INNER JOIN ai_sample s ON s.id = l.sample_id
            WHERE l.label_version = #{labelVersion}
              AND l.label_status = 'MATURED'
              AND l.execution_status = 'EXECUTED'
              AND l.label_available_at <= #{asOfTime}
              AND s.as_of_time <= #{asOfTime}
            GROUP BY l.horizon_trading_days
            UNION ALL
            SELECT CONVERT('REGIME' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS dimension_type,
                   CONVERT(s.market_regime USING utf8mb4) COLLATE utf8mb4_unicode_ci AS dimension_key,
                   COUNT(DISTINCT s.trade_date) AS metric_count
            FROM ai_sample s
            WHERE s.quality_status = 'READY' AND s.tradable_status = 'TRADABLE'
              AND s.as_of_time <= #{asOfTime}
            GROUP BY s.market_regime
            """)
    List<AiTrainingReadinessMetric> selectTrainingReadinessMetrics(
            @Param("labelVersion") String labelVersion,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT
                s.feature_version,
                l.label_version,
                l.calendar_version,
                MIN(s.trade_date) AS first_trade_date,
                MAX(s.trade_date) AS last_trade_date,
                COUNT(*) AS row_count,
                COUNT(DISTINCT s.trade_date) AS trading_day_count
            FROM ai_sample s
            INNER JOIN ai_sample_label l ON l.sample_id = s.id
            WHERE l.label_version = #{labelVersion}
              AND l.horizon_trading_days = #{horizonDays}
              AND l.label_status = 'MATURED'
              AND l.execution_status = 'EXECUTED'
              AND s.as_of_time <= #{asOfTime}
              AND l.label_available_at <= #{asOfTime}
            GROUP BY s.feature_version, l.label_version, l.calendar_version
            ORDER BY row_count DESC, last_trade_date DESC
            LIMIT 1
            """)
    AiTrainingSourceSummary selectDominantSourceSummary(
            @Param("labelVersion") String labelVersion,
            @Param("horizonDays") Integer horizonDays,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT DISTINCT s.trade_date
            FROM ai_sample s
            INNER JOIN ai_sample_label l ON l.sample_id = s.id
            WHERE s.feature_version = #{featureVersion}
              AND l.label_version = #{labelVersion}
              AND l.calendar_version = #{calendarVersion}
              AND l.horizon_trading_days = #{horizonDays}
              AND l.label_status = 'MATURED'
              AND l.execution_status = 'EXECUTED'
              AND s.as_of_time <= #{asOfTime}
              AND l.label_available_at <= #{asOfTime}
            ORDER BY s.trade_date
            """)
    List<LocalDate> selectEligibleTradeDates(
            @Param("featureVersion") String featureVersion,
            @Param("labelVersion") String labelVersion,
            @Param("calendarVersion") String calendarVersion,
            @Param("horizonDays") Integer horizonDays,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT
                s.id AS sample_id,
                l.id AS sample_label_id,
                s.stock_code,
                s.trade_date,
                s.as_of_time AS sample_as_of_time,
                l.label_available_at,
                s.feature_version,
                l.label_version,
                l.calendar_version,
                l.horizon_trading_days,
                s.feature_snapshot,
                l.net_return,
                l.excess_return,
                l.actual_direction,
                l.execution_status,
                s.source_fingerprint AS feature_fingerprint,
                l.input_fingerprint AS label_fingerprint
            FROM ai_sample s
            INNER JOIN ai_sample_label l ON l.sample_id = s.id
            WHERE s.feature_version = #{query.featureVersion}
              AND l.label_version = #{query.labelVersion}
              AND l.calendar_version = #{query.calendarVersion}
              AND l.label_status = 'MATURED'
              AND l.execution_status = 'EXECUTED'
              AND l.horizon_trading_days = #{query.horizonTradingDays}
              AND s.trade_date BETWEEN #{query.startDate} AND #{query.endDate}
              AND s.as_of_time <= #{query.asOfTime}
              AND l.label_available_at <= #{query.asOfTime}
            ORDER BY s.trade_date, s.stock_code, s.id, l.horizon_trading_days, l.id
            FOR SHARE
            """)
    List<AiTrainingDatasetSource> selectEligibleSources(
            @Param("query") AiTrainingDatasetSourceQuery query
    );

    @Select("""
            <script>
            SELECT
                s.id AS sample_id,
                l.id AS sample_label_id,
                s.stock_code,
                s.trade_date,
                s.as_of_time AS sample_as_of_time,
                l.label_available_at,
                s.feature_version,
                l.label_version,
                l.calendar_version,
                l.horizon_trading_days,
                s.feature_snapshot,
                l.net_return,
                l.excess_return,
                l.actual_direction,
                l.execution_status,
                s.source_fingerprint AS feature_fingerprint,
                l.input_fingerprint AS label_fingerprint
            FROM ai_sample s
            INNER JOIN ai_sample_label l ON l.sample_id = s.id
            WHERE s.feature_version = #{query.featureVersion}
              AND l.label_version = #{query.labelVersion}
              AND l.calendar_version = #{query.calendarVersion}
              AND l.label_status = 'MATURED'
              AND l.execution_status = 'EXECUTED'
              AND l.horizon_trading_days = #{query.horizonTradingDays}
              AND s.trade_date BETWEEN #{query.startDate} AND #{query.endDate}
              AND s.as_of_time &lt;= #{query.asOfTime}
              AND l.label_available_at &lt;= #{query.asOfTime}
            <if test="afterTradeDate != null">
              AND (s.trade_date, s.stock_code, s.id, l.id) &gt;
                  (#{afterTradeDate}, #{afterStockCode}, #{afterSampleId}, #{afterLabelId})
            </if>
            ORDER BY s.trade_date, s.stock_code, s.id, l.id
            LIMIT #{limit}
            </script>
            """)
    List<AiTrainingDatasetSource> selectEligibleSourcesPage(
            @Param("query") AiTrainingDatasetSourceQuery query,
            @Param("afterTradeDate") LocalDate afterTradeDate,
            @Param("afterStockCode") String afterStockCode,
            @Param("afterSampleId") Long afterSampleId,
            @Param("afterLabelId") Long afterLabelId,
            @Param("limit") int limit
    );

    @Insert("""
            <script>
            INSERT INTO ai_training_dataset_item (
                training_dataset_id, sample_id, sample_label_id, split_type, sequence_no,
                sample_as_of_time, label_available_at, feature_fingerprint,
                label_fingerprint, included_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.trainingDatasetId}, #{item.sampleId}, #{item.sampleLabelId},
                    #{item.splitType}, #{item.sequenceNo}, #{item.sampleAsOfTime},
                    #{item.labelAvailableAt}, #{item.featureFingerprint},
                    #{item.labelFingerprint}, #{item.includedAt}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiTrainingDatasetItem> items);

    @Select("""
            SELECT * FROM ai_training_dataset_item
            WHERE training_dataset_id = #{datasetId}
            ORDER BY CASE split_type WHEN 'TRAIN' THEN 1 WHEN 'VALIDATION' THEN 2 ELSE 3 END,
                     sequence_no
            FOR SHARE
            """)
    List<AiTrainingDatasetItem> selectByDatasetForShare(@Param("datasetId") Long datasetId);
}
