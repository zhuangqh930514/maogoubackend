package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiLearningCoverageDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface AiLearningCoverageDailyMapper extends BaseMapper<AiLearningCoverageDaily> {

    @Select("""
            SELECT COUNT(*)
            FROM ai_prediction p
            INNER JOIN ai_sample_label l ON l.sample_id = p.sample_id
              AND l.horizon_trading_days = p.horizon_trading_days
              AND l.label_version = #{labelVersion} AND l.label_status = 'MATURED' AND l.is_current = 1
              AND l.label_available_at <= #{tradeDate}
            WHERE p.horizon_trading_days = #{horizonDays}
              AND p.trade_date < #{tradeDate}
              AND p.trade_date >= DATE_SUB(#{tradeDate}, INTERVAL #{lookbackDays} DAY)
            """)
    long countEligibleDuePredictions(@Param("tradeDate") LocalDate tradeDate,
                                     @Param("horizonDays") int horizonDays,
                                     @Param("lookbackDays") int lookbackDays,
                                     @Param("labelVersion") String labelVersion);

    @Select("""
            SELECT COUNT(*)
            FROM ai_prediction p
            INNER JOIN ai_sample_label l ON l.sample_id = p.sample_id
              AND l.horizon_trading_days = p.horizon_trading_days
              AND l.label_version = #{labelVersion} AND l.label_status = 'MATURED' AND l.is_current = 1
              AND l.label_available_at <= #{tradeDate}
            INNER JOIN ai_prediction_evaluation e ON e.prediction_id = p.id AND e.sample_label_id = l.id
              AND e.evaluation_version = #{evaluationVersion}
              AND e.evaluation_status IN ('EVALUATED', 'SUCCESS', 'COMPLETED')
            WHERE p.horizon_trading_days = #{horizonDays}
              AND p.trade_date < #{tradeDate}
              AND p.trade_date >= DATE_SUB(#{tradeDate}, INTERVAL #{lookbackDays} DAY)
            """)
    long countEvaluatedDuePredictions(@Param("tradeDate") LocalDate tradeDate,
                                      @Param("horizonDays") int horizonDays,
                                      @Param("lookbackDays") int lookbackDays,
                                      @Param("labelVersion") String labelVersion,
                                      @Param("evaluationVersion") String evaluationVersion);

    @Insert("""
            INSERT INTO ai_learning_coverage_daily (
                trade_date, horizon_trading_days, pipeline_run_id, eligible_prediction_count, mature_label_count,
                evaluation_count, direction_assessed_count, plan_due_count, plan_trigger_checked_count,
                plan_outcome_evaluated_count, unavailable_count, retryable_count, failed_count, coverage_rate,
                coverage_status, error_summary, generated_at, created_at, updated_at
            ) VALUES (
                #{value.tradeDate}, #{value.horizonTradingDays}, #{value.pipelineRunId}, #{value.eligiblePredictionCount},
                #{value.matureLabelCount}, #{value.evaluationCount}, #{value.directionAssessedCount}, #{value.planDueCount},
                #{value.planTriggerCheckedCount}, #{value.planOutcomeEvaluatedCount}, #{value.unavailableCount},
                #{value.retryableCount}, #{value.failedCount}, #{value.coverageRate}, #{value.coverageStatus},
                #{value.errorSummary}, #{value.generatedAt}, #{value.createdAt}, #{value.updatedAt}
            ) ON DUPLICATE KEY UPDATE
                eligible_prediction_count = VALUES(eligible_prediction_count), mature_label_count = VALUES(mature_label_count),
                evaluation_count = VALUES(evaluation_count), direction_assessed_count = VALUES(direction_assessed_count),
                unavailable_count = VALUES(unavailable_count), retryable_count = VALUES(retryable_count),
                failed_count = VALUES(failed_count), coverage_rate = VALUES(coverage_rate),
                coverage_status = VALUES(coverage_status), error_summary = VALUES(error_summary), generated_at = VALUES(generated_at),
                updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("value") AiLearningCoverageDaily value);
}
