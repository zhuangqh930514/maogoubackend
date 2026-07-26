-- Forward-only upgrade for the AI research verification/evolution loop.
-- Old PENDING/VERIFIED/NO_ACTION states remain readable during the transition.

CREATE TABLE IF NOT EXISTS ai_learning_coverage_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trade_date DATE NOT NULL,
    horizon_trading_days INT NOT NULL,
    pipeline_run_id BIGINT NULL,
    eligible_prediction_count INT NOT NULL DEFAULT 0,
    mature_label_count INT NOT NULL DEFAULT 0,
    evaluation_count INT NOT NULL DEFAULT 0,
    direction_assessed_count INT NOT NULL DEFAULT 0,
    plan_due_count INT NOT NULL DEFAULT 0,
    plan_trigger_checked_count INT NOT NULL DEFAULT 0,
    plan_outcome_evaluated_count INT NOT NULL DEFAULT 0,
    unavailable_count INT NOT NULL DEFAULT 0,
    retryable_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    coverage_rate DECIMAL(10,4) NULL,
    coverage_status VARCHAR(32) NOT NULL,
    error_summary MEDIUMTEXT NULL,
    generated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_learning_coverage_trade_horizon_run (trade_date, horizon_trading_days, pipeline_run_id),
    KEY idx_learning_coverage_status (trade_date, horizon_trading_days, coverage_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ai_daily_decision_plan
    ADD COLUMN IF NOT EXISTS trigger_checked_at DATETIME(3) NULL,
    ADD COLUMN IF NOT EXISTS outcome_checked_at DATETIME(3) NULL,
    ADD COLUMN IF NOT EXISTS trigger_source_provider VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS trigger_source_fingerprint VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS outcome_source_provider VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS outcome_source_fingerprint VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at DATETIME(3) NULL;

ALTER TABLE ai_daily_decision_plan_review
    ADD COLUMN IF NOT EXISTS trigger_checked_at DATETIME(3) NULL,
    ADD COLUMN IF NOT EXISTS outcome_checked_at DATETIME(3) NULL,
    ADD COLUMN IF NOT EXISTS trigger_source_provider VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS trigger_source_fingerprint VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS outcome_source_provider VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS outcome_source_fingerprint VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at DATETIME(3) NULL;

CREATE INDEX IF NOT EXISTS idx_daily_plan_trigger_due
    ON ai_daily_decision_plan (user_id, status, target_trade_date);
CREATE INDEX IF NOT EXISTS idx_daily_plan_outcome_due
    ON ai_daily_decision_plan (user_id, status, outcome_trade_date);
CREATE INDEX IF NOT EXISTS idx_factor_performance_horizon_current
    ON ai_factor_performance (factor_definition_id, horizon_trading_days, market_regime, is_current, window_end_date);
