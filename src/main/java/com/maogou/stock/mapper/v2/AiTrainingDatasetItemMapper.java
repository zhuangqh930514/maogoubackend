package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiTrainingDatasetItem;
import com.maogou.stock.domain.entity.v2.AiTrainingDatasetSource;
import com.maogou.stock.domain.entity.v2.AiTrainingDatasetSourceQuery;
import com.maogou.stock.domain.entity.v2.AiTrainingSourceSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AiTrainingDatasetItemMapper extends BaseMapper<AiTrainingDatasetItem> {

    @Select("""
            SELECT
                s.feature_version,
                l.label_version,
                l.calendar_version,
                MIN(s.trade_date) AS first_trade_date,
                MAX(s.trade_date) AS last_trade_date,
                COUNT(*) AS row_count,
                COUNT(DISTINCT s.trade_date) AS trading_day_count
            FROM ai_sample_v2 s
            INNER JOIN ai_label_v2 l ON l.sample_id = s.id AND l.user_id = s.user_id
            WHERE s.user_id = #{userId}
              AND l.label_version = #{labelVersion}
              AND l.horizon_days = #{horizonDays}
              AND l.label_status = 'VERIFIED'
              AND s.as_of_time <= #{asOfTime}
              AND COALESCE(l.verified_at, l.matured_at) <= #{asOfTime}
            GROUP BY s.feature_version, l.label_version, l.calendar_version
            ORDER BY row_count DESC, last_trade_date DESC
            LIMIT 1
            """)
    AiTrainingSourceSummary selectDominantSourceSummary(
            @Param("userId") Long userId,
            @Param("labelVersion") String labelVersion,
            @Param("horizonDays") Integer horizonDays,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT DISTINCT s.trade_date
            FROM ai_sample_v2 s
            INNER JOIN ai_label_v2 l ON l.sample_id = s.id AND l.user_id = s.user_id
            WHERE s.user_id = #{userId}
              AND s.feature_version = #{featureVersion}
              AND l.label_version = #{labelVersion}
              AND l.calendar_version = #{calendarVersion}
              AND l.horizon_days = #{horizonDays}
              AND l.label_status = 'VERIFIED'
              AND s.as_of_time <= #{asOfTime}
              AND COALESCE(l.verified_at, l.matured_at) <= #{asOfTime}
            ORDER BY s.trade_date
            """)
    List<LocalDate> selectEligibleTradeDates(
            @Param("userId") Long userId,
            @Param("featureVersion") String featureVersion,
            @Param("labelVersion") String labelVersion,
            @Param("calendarVersion") String calendarVersion,
            @Param("horizonDays") Integer horizonDays,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT
                s.id AS sample_id,
                l.id AS label_id,
                s.user_id,
                s.stock_code,
                s.trade_date,
                s.as_of_time AS sample_as_of_time,
                COALESCE(l.verified_at, l.matured_at) AS label_available_at,
                s.feature_version,
                l.label_version,
                l.calendar_version,
                l.horizon_days,
                s.feature_snapshot,
                l.net_return,
                l.excess_return,
                l.label_score,
                l.hit_direction,
                s.source_fingerprint AS feature_fingerprint,
                l.input_fingerprint AS label_fingerprint
            FROM ai_sample_v2 s
            INNER JOIN ai_label_v2 l ON l.sample_id = s.id AND l.user_id = s.user_id
            WHERE s.user_id = #{query.userId}
              AND s.feature_version = #{query.featureVersion}
              AND l.label_version = #{query.labelVersion}
              AND l.calendar_version = #{query.calendarVersion}
              AND l.label_status = 'VERIFIED'
              AND l.horizon_days = #{query.maxHorizonDays}
              AND s.trade_date BETWEEN #{query.startDate} AND #{query.endDate}
              AND s.as_of_time <= #{query.asOfTime}
              AND COALESCE(l.verified_at, l.matured_at) <= #{query.asOfTime}
            ORDER BY s.trade_date, s.stock_code, s.id, l.horizon_days, l.id
            FOR SHARE
            """)
    List<AiTrainingDatasetSource> selectEligibleSources(
            @Param("query") AiTrainingDatasetSourceQuery query
    );

    @Insert("""
            <script>
            INSERT INTO ai_training_dataset_item (
                training_dataset_id, sample_id, label_id, split_type, sequence_no,
                sample_as_of_time, label_available_at, feature_fingerprint,
                label_fingerprint, included_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.trainingDatasetId}, #{item.sampleId}, #{item.labelId},
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
