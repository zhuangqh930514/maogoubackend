package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiTradeFactorFeedback;
import org.apache.ibatis.annotations.Insert;

public interface AiTradeFactorFeedbackMapper extends BaseMapper<AiTradeFactorFeedback> {

    @Insert("""
            INSERT INTO ai_trade_factor_feedback (
                user_id, trade_rule_config_id, factor_code, factor_name, factor_group,
                rule_code, rule_type, horizon_trading_days, market_regime,
                window_start_date, window_end_date, sample_count, effective_count,
                effectiveness_rate, avg_net_action_return, avg_excess_return,
                confidence_level, feedback_scope, input_fingerprint, last_evaluated_at,
                created_at, updated_at
            ) VALUES (
                #{userId}, #{tradeRuleConfigId}, #{factorCode}, #{factorName}, #{factorGroup},
                #{ruleCode}, #{ruleType}, #{horizonDays}, #{marketRegime},
                #{windowStartDate}, #{windowEndDate}, #{sampleCount}, #{effectiveCount},
                #{effectivenessRate}, #{avgNetActionReturn}, #{avgExcessReturn},
                #{confidenceLevel}, #{feedbackScope}, #{inputFingerprint}, #{lastEvaluatedAt},
                #{createdAt}, #{updatedAt}
            ) ON DUPLICATE KEY UPDATE
                factor_name = VALUES(factor_name),
                factor_group = VALUES(factor_group),
                sample_count = VALUES(sample_count),
                effective_count = VALUES(effective_count),
                effectiveness_rate = VALUES(effectiveness_rate),
                avg_net_action_return = VALUES(avg_net_action_return),
                avg_excess_return = VALUES(avg_excess_return),
                confidence_level = VALUES(confidence_level),
                feedback_scope = VALUES(feedback_scope),
                input_fingerprint = VALUES(input_fingerprint),
                last_evaluated_at = VALUES(last_evaluated_at),
                updated_at = VALUES(updated_at)
            """)
    int upsert(AiTradeFactorFeedback feedback);
}
