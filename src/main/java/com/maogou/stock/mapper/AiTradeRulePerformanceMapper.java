package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiTradeRulePerformance;
import org.apache.ibatis.annotations.Insert;

public interface AiTradeRulePerformanceMapper extends BaseMapper<AiTradeRulePerformance> {

    @Insert("""
            INSERT INTO ai_trade_rule_performance (
                user_id, trade_rule_config_id, rule_code, rule_type,
                horizon_trading_days, market_regime, window_start_date, window_end_date,
                sample_count, effective_count, effectiveness_rate,
                avg_post_trigger_return, avg_adverse_return, avg_net_action_return,
                avg_excess_return, avg_transaction_cost_bps, wilson_lower_bound,
                learned_weight, confidence_level, feedback_scope, input_fingerprint,
                last_evaluated_at, created_at, updated_at
            ) VALUES (
                #{userId}, #{tradeRuleConfigId}, #{ruleCode}, #{ruleType},
                #{horizonDays}, #{marketRegime}, #{windowStartDate}, #{windowEndDate},
                #{sampleCount}, #{effectiveCount}, #{effectivenessRate},
                #{avgPostTriggerReturn}, #{avgAdverseReturn}, #{avgNetActionReturn},
                #{avgExcessReturn}, #{avgTransactionCostBps}, #{wilsonLowerBound},
                #{learnedWeight}, #{confidenceLevel}, #{feedbackScope}, #{inputFingerprint},
                #{lastEvaluatedAt}, #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE
                sample_count = VALUES(sample_count),
                effective_count = VALUES(effective_count),
                effectiveness_rate = VALUES(effectiveness_rate),
                avg_post_trigger_return = VALUES(avg_post_trigger_return),
                avg_adverse_return = VALUES(avg_adverse_return),
                avg_net_action_return = VALUES(avg_net_action_return),
                avg_excess_return = VALUES(avg_excess_return),
                avg_transaction_cost_bps = VALUES(avg_transaction_cost_bps),
                wilson_lower_bound = VALUES(wilson_lower_bound),
                learned_weight = VALUES(learned_weight),
                confidence_level = VALUES(confidence_level),
                feedback_scope = VALUES(feedback_scope),
                input_fingerprint = VALUES(input_fingerprint),
                last_evaluated_at = VALUES(last_evaluated_at),
                updated_at = VALUES(updated_at)
            """)
    int upsert(AiTradeRulePerformance performance);
}
