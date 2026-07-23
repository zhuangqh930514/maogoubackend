package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface AiPredictionEvaluationMapper extends BaseMapper<AiPredictionEvaluation> {

    class StrategyEvaluationSummary {
        public Long totalCount;
        public Long assessedCount;
        public Long correctCount;
    }

    class StockEvaluationSummary {
        public String stockCode;
        public Long totalCount;
        public Long correctCount;
    }

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
            SELECT COUNT(*) AS total_count,
                   COALESCE(SUM(CASE WHEN e.direction_correct IS NOT NULL THEN 1 ELSE 0 END), 0) AS assessed_count,
                   COALESCE(SUM(CASE WHEN e.direction_correct = 1 THEN 1 ELSE 0 END), 0) AS correct_count
            FROM ai_prediction p FORCE INDEX (idx_prediction_strategy_trade_evidence)
            INNER JOIN ai_prediction_evaluation e FORCE INDEX (idx_prediction_evaluation_decision_summary)
                    ON e.prediction_id = p.id
            WHERE p.strategy_release_id = #{strategyReleaseId}
              AND p.trade_date < #{tradeDate}
              AND e.evaluation_status IN ('EVALUATED', 'SUCCESS', 'COMPLETED')
            """)
    StrategyEvaluationSummary selectDecisionEvidenceSummary(
            @Param("strategyReleaseId") Long strategyReleaseId,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT p.stock_code AS stock_code,
                   COUNT(*) AS total_count,
                   COALESCE(SUM(CASE WHEN e.direction_correct = 1 THEN 1 ELSE 0 END), 0) AS correct_count
            FROM ai_prediction p FORCE INDEX (idx_prediction_strategy_trade_evidence)
            INNER JOIN ai_prediction_evaluation e FORCE INDEX (idx_prediction_evaluation_decision_summary)
                    ON e.prediction_id = p.id
            WHERE p.strategy_release_id = #{strategyReleaseId}
              AND p.trade_date < #{tradeDate}
              AND e.evaluation_status IN ('EVALUATED', 'SUCCESS', 'COMPLETED')
              AND e.direction_correct IS NOT NULL
            GROUP BY p.stock_code
            """)
    List<StockEvaluationSummary> selectDecisionEvidenceByStock(
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
