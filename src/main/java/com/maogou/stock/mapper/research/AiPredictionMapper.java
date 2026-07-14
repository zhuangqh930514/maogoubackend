package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiPrediction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.LocalDate;

public interface AiPredictionMapper extends BaseMapper<AiPrediction> {

    @Insert("""
            <script>
            INSERT INTO ai_prediction (
                sample_id, strategy_release_id, model_version_id, stock_code, trade_date,
                sample_phase, inference_mode, input_fingerprint, horizon_trading_days, expected_return,
                expected_excess_return, probability_up, probability_down, calibrated_confidence,
                score, risk_score, rank_no, action, action_bucket, target_direction, abstain_reason,
                reason_json, idempotency_key, predicted_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.sampleId}, #{item.strategyReleaseId}, #{item.modelVersionId},
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
    int insertBatchImmutable(@Param("items") List<AiPrediction> items);

    @Select("""
            <script>
            SELECT *
            FROM ai_prediction
            WHERE idempotency_key IN
              <foreach collection="keys" item="key" open="(" separator="," close=")">
                  #{key}
              </foreach>
            FOR SHARE
            </script>
            """)
    List<AiPrediction> selectByIdempotencyKeysForShare(
            @Param("keys") List<String> keys
    );

    @Select("""
            SELECT p.*
            FROM ai_prediction p
            LEFT JOIN ai_prediction_evaluation e
              ON e.prediction_id = p.id
             AND e.evaluation_version = #{evaluationVersion}
            WHERE p.trade_date < #{tradeDate}
              AND p.horizon_trading_days IN (1, 2, 3, 5)
              AND e.id IS NULL
            ORDER BY p.trade_date, p.sample_phase, p.horizon_trading_days, p.id
            LIMIT #{limit}
            """)
    List<AiPrediction> selectUnevaluatedCandidates(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("evaluationVersion") String evaluationVersion,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT * FROM ai_prediction
            WHERE strategy_release_id = #{strategyReleaseId}
              AND horizon_trading_days IN (1, 2, 3)
              AND sample_id IN
              <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
                #{sampleId}
              </foreach>
            ORDER BY sample_id, horizon_trading_days
            </script>
            """)
    List<AiPrediction> selectForDailyDecision(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("strategyReleaseId") Long strategyReleaseId
    );
}
