package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiDriftEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiDriftEventMapper extends BaseMapper<AiDriftEvent> {

    @Insert("""
            <script>
            INSERT INTO ai_drift_event (
                user_id, factor_performance_id, model_version_id, strategy_release_id,
                shadow_evaluation_id, event_fingerprint, event_type, subject_type, subject_key,
                detector_version, window_start_date, window_end_date, metric_name, baseline_value,
                observed_value, threshold_value, severity, status, evidence_json, detected_at,
                acknowledged_at, resolved_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.userId}, #{item.factorPerformanceId}, #{item.modelVersionId},
                    #{item.strategyReleaseId}, #{item.shadowEvaluationId}, #{item.eventFingerprint},
                    #{item.eventType}, #{item.subjectType}, #{item.subjectKey},
                    #{item.detectorVersion}, #{item.windowStartDate}, #{item.windowEndDate},
                    #{item.metricName}, #{item.baselineValue}, #{item.observedValue},
                    #{item.thresholdValue}, #{item.severity}, #{item.status}, #{item.evidenceJson},
                    #{item.detectedAt}, #{item.acknowledgedAt}, #{item.resolvedAt}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiDriftEvent> items);

    @Select("""
            <script>
            SELECT * FROM ai_drift_event
            WHERE user_id = #{userId}
              AND event_fingerprint IN
              <foreach collection="fingerprints" item="fingerprint" open="(" separator="," close=")">
                  #{fingerprint}
              </foreach>
            FOR SHARE
            </script>
            """)
    List<AiDriftEvent> selectByFingerprintsForShare(
            @Param("userId") Long userId,
            @Param("fingerprints") List<String> fingerprints
    );
}
