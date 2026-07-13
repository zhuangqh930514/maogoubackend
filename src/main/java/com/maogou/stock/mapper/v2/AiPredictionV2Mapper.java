package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.LocalDate;

public interface AiPredictionV2Mapper extends BaseMapper<AiPredictionV2> {

    @Insert("""
            <script>
            INSERT INTO ai_prediction_v2 (
                user_id, sample_id, strategy_release_id, model_version_id, stock_code, trade_date,
                sample_phase, inference_mode, input_fingerprint, horizon_days, expected_return,
                expected_excess_return, probability_up, probability_down, calibrated_confidence,
                score, risk_score, rank_no, action, action_bucket, target_direction, abstain_reason,
                reason_json, idempotency_key, predicted_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.userId}, #{item.sampleId}, #{item.strategyReleaseId}, #{item.modelVersionId},
                    #{item.stockCode}, #{item.tradeDate}, #{item.samplePhase}, #{item.inferenceMode},
                    #{item.inputFingerprint}, #{item.horizonDays}, #{item.expectedReturn},
                    #{item.expectedExcessReturn}, #{item.probabilityUp}, #{item.probabilityDown},
                    #{item.calibratedConfidence}, #{item.score}, #{item.riskScore}, #{item.rankNo},
                    #{item.action}, #{item.actionBucket}, #{item.targetDirection}, #{item.abstainReason},
                    #{item.reasonJson}, #{item.idempotencyKey}, #{item.predictedAt}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiPredictionV2> items);

    @Select("""
            <script>
            SELECT *
            FROM ai_prediction_v2
            WHERE user_id = #{userId}
              AND idempotency_key IN
              <foreach collection="keys" item="key" open="(" separator="," close=")">
                  #{key}
              </foreach>
            FOR SHARE
            </script>
            """)
    List<AiPredictionV2> selectByIdempotencyKeysForShare(
            @Param("userId") Long userId,
            @Param("keys") List<String> keys
    );

    @Select("""
            SELECT p.*
            FROM ai_prediction_v2 p
            LEFT JOIN ai_label_v2 l
              ON l.user_id = p.user_id
             AND l.prediction_id = p.id
             AND l.horizon_days = p.horizon_days
             AND l.label_version = #{labelVersion}
            WHERE p.user_id = #{userId}
              AND p.trade_date < #{tradeDate}
              AND p.horizon_days IN (1, 3, 5)
              AND l.id IS NULL
            ORDER BY p.trade_date, p.sample_phase, p.horizon_days, p.id
            LIMIT #{limit}
            """)
    List<AiPredictionV2> selectUnverifiedCandidates(
            @Param("userId") Long userId,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("labelVersion") String labelVersion,
            @Param("limit") int limit
    );
}
