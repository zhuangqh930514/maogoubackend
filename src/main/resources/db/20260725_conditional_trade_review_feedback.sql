-- Conditional-plan reviews are candidate-only learning evidence. They must not mutate
-- the active Champion rule configuration or formal factor labels directly.
SET @schema_name = DATABASE();

SET @add_review_transaction_cost_bps = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_plan_review'
             AND column_name = 'transaction_cost_bps'),
    'SELECT 1',
    'ALTER TABLE ai_trade_plan_review ADD COLUMN transaction_cost_bps DECIMAL(10,4) NULL AFTER max_adverse_return'
);
PREPARE statement FROM @add_review_transaction_cost_bps;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_review_net_action_return = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_plan_review'
             AND column_name = 'net_action_return'),
    'SELECT 1',
    'ALTER TABLE ai_trade_plan_review ADD COLUMN net_action_return DECIMAL(12,6) NULL AFTER transaction_cost_bps'
);
PREPARE statement FROM @add_review_net_action_return;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_review_benchmark_return = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_plan_review'
             AND column_name = 'benchmark_return'),
    'SELECT 1',
    'ALTER TABLE ai_trade_plan_review ADD COLUMN benchmark_return DECIMAL(12,6) NULL AFTER net_action_return'
);
PREPARE statement FROM @add_review_benchmark_return;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_review_excess_return = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_plan_review'
             AND column_name = 'excess_return'),
    'SELECT 1',
    'ALTER TABLE ai_trade_plan_review ADD COLUMN excess_return DECIMAL(12,6) NULL AFTER benchmark_return'
);
PREPARE statement FROM @add_review_excess_return;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_review_feedback_index = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_plan_review'
             AND index_name = 'idx_trade_plan_review_feedback'),
    'SELECT 1',
    'ALTER TABLE ai_trade_plan_review ADD KEY idx_trade_plan_review_feedback
        (user_id, status, triggered_rule_code, horizon_trading_days, report_date)'
);
PREPARE statement FROM @add_review_feedback_index;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_performance_net_action_return = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_rule_performance'
             AND column_name = 'avg_net_action_return'),
    'SELECT 1',
    'ALTER TABLE ai_trade_rule_performance ADD COLUMN avg_net_action_return DECIMAL(12,6) NOT NULL DEFAULT 0 AFTER avg_adverse_return'
);
PREPARE statement FROM @add_performance_net_action_return;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_performance_excess_return = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_rule_performance'
             AND column_name = 'avg_excess_return'),
    'SELECT 1',
    'ALTER TABLE ai_trade_rule_performance ADD COLUMN avg_excess_return DECIMAL(12,6) NOT NULL DEFAULT 0 AFTER avg_net_action_return'
);
PREPARE statement FROM @add_performance_excess_return;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_performance_cost = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_rule_performance'
             AND column_name = 'avg_transaction_cost_bps'),
    'SELECT 1',
    'ALTER TABLE ai_trade_rule_performance ADD COLUMN avg_transaction_cost_bps DECIMAL(10,4) NOT NULL DEFAULT 0 AFTER avg_excess_return'
);
PREPARE statement FROM @add_performance_cost;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_performance_wilson = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_rule_performance'
             AND column_name = 'wilson_lower_bound'),
    'SELECT 1',
    'ALTER TABLE ai_trade_rule_performance ADD COLUMN wilson_lower_bound DECIMAL(10,4) NOT NULL DEFAULT 0 AFTER avg_transaction_cost_bps'
);
PREPARE statement FROM @add_performance_wilson;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_performance_scope = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = @schema_name AND table_name = 'ai_trade_rule_performance'
             AND column_name = 'feedback_scope'),
    'SELECT 1',
    'ALTER TABLE ai_trade_rule_performance ADD COLUMN feedback_scope VARCHAR(24) NOT NULL DEFAULT ''CANDIDATE_ONLY'' AFTER confidence_level'
);
PREPARE statement FROM @add_performance_scope;
EXECUTE statement;
DEALLOCATE PREPARE statement;

CREATE TABLE IF NOT EXISTS ai_trade_factor_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trade_rule_config_id BIGINT NOT NULL,
    factor_code VARCHAR(96) NOT NULL,
    factor_name VARCHAR(128) NOT NULL,
    factor_group VARCHAR(64) NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    horizon_trading_days INT NOT NULL,
    market_regime VARCHAR(32) NOT NULL,
    window_start_date DATE NOT NULL,
    window_end_date DATE NOT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    effective_count INT NOT NULL DEFAULT 0,
    effectiveness_rate DECIMAL(10,4) NOT NULL DEFAULT 0,
    avg_net_action_return DECIMAL(12,6) NOT NULL DEFAULT 0,
    avg_excess_return DECIMAL(12,6) NOT NULL DEFAULT 0,
    confidence_level VARCHAR(24) NOT NULL DEFAULT 'LOW_SAMPLE',
    feedback_scope VARCHAR(24) NOT NULL DEFAULT 'CANDIDATE_ONLY',
    input_fingerprint VARCHAR(128) NOT NULL,
    last_evaluated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trade_factor_feedback_window (
        user_id, trade_rule_config_id, factor_code, rule_code, horizon_trading_days,
        market_regime, window_start_date, window_end_date
    ),
    KEY idx_trade_factor_feedback_lookup (
        user_id, factor_code, market_regime, sample_count, last_evaluated_at
    ),
    CONSTRAINT chk_trade_factor_feedback_horizon CHECK (horizon_trading_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_trade_factor_feedback_config
        FOREIGN KEY (user_id, trade_rule_config_id)
        REFERENCES ai_trade_rule_config (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
