package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface AiPredictionEvaluationMapper extends BaseMapper<AiPredictionEvaluation> {

    @Select("""
            SELECT e.*, p.stock_code AS stock_code,
                   p.horizon_trading_days AS horizon_days
            FROM ai_prediction_evaluation e
            INNER JOIN ai_prediction p ON p.id = e.prediction_id
            WHERE p.strategy_release_id = #{strategyReleaseId}
              AND p.trade_date < #{tradeDate}
              AND e.evaluation_status IN ('EVALUATED', 'SUCCESS', 'COMPLETED')
            ORDER BY e.evaluated_at, e.id
            """)
    List<AiPredictionEvaluation> selectForDecisionEvidence(
            @Param("strategyReleaseId") Long strategyReleaseId,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT * FROM ai_prediction_evaluation
            WHERE prediction_id = #{predictionId} AND sample_label_id = #{sampleLabelId}
              AND evaluation_status IN ('EVALUATED', 'SUCCESS', 'COMPLETED')
            ORDER BY evaluated_at DESC, id DESC LIMIT 1
            """)
    AiPredictionEvaluation selectForReview(
            @Param("predictionId") Long predictionId,
            @Param("sampleLabelId") Long sampleLabelId
    );
}
