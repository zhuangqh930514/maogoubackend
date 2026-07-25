-- Makes every formal daily decision reviewable even when no AI report was generated.
-- These tables are deliberately separate from ai_trade_plan_review, whose report_id
-- foreign key represents AI-report lineage rather than deterministic-policy lineage.
CREATE TABLE IF NOT EXISTS ai_daily_decision_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    decision_item_id BIGINT NOT NULL,
    sample_id BIGINT NULL,
    trade_rule_config_id BIGINT NULL,
    stock_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    horizon_days INT NOT NULL,
    plan_source VARCHAR(32) NOT NULL,
    official_action VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    target_trade_date DATE NULL,
    outcome_trade_date DATE NULL,
    plan_json MEDIUMTEXT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    source_provider VARCHAR(64) NULL,
    source_as_of DATETIME(3) NULL,
    unavailable_reason VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_daily_decision_plan_item_horizon (user_id, decision_item_id, horizon_days),
    UNIQUE KEY uk_daily_decision_plan_user_id (user_id, id),
    KEY idx_daily_decision_plan_pending (user_id, status, outcome_trade_date),
    KEY idx_daily_decision_plan_stock_date (user_id, stock_code, trade_date),
    CONSTRAINT chk_daily_decision_plan_horizon CHECK (horizon_days IN (1, 2, 3)),
    CONSTRAINT fk_daily_decision_plan_item
        FOREIGN KEY (user_id, decision_item_id) REFERENCES ai_daily_decision_item (user_id, id),
    CONSTRAINT fk_daily_decision_plan_sample FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_daily_decision_plan_rule_config
        FOREIGN KEY (user_id, trade_rule_config_id) REFERENCES ai_trade_rule_config (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_daily_decision_plan_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    decision_plan_id BIGINT NOT NULL,
    trigger_trade_date DATE NULL,
    outcome_trade_date DATE NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    triggered_rule_code VARCHAR(64) NULL,
    triggered_state VARCHAR(64) NULL,
    suggested_action VARCHAR(16) NULL,
    trigger_price DECIMAL(18,4) NULL,
    outcome_price DECIMAL(18,4) NULL,
    post_trigger_return DECIMAL(12,6) NULL,
    max_favorable_return DECIMAL(12,6) NULL,
    max_adverse_return DECIMAL(12,6) NULL,
    transaction_cost_bps DECIMAL(10,4) NULL,
    net_action_return DECIMAL(12,6) NULL,
    benchmark_return DECIMAL(12,6) NULL,
    excess_return DECIMAL(12,6) NULL,
    action_effective TINYINT NULL,
    review_score DECIMAL(10,4) NULL,
    actual_metrics_json MEDIUMTEXT NULL,
    feedback_json MEDIUMTEXT NULL,
    feedback_summary VARCHAR(1024) NULL,
    evaluated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_daily_decision_plan_review (user_id, decision_plan_id),
    KEY idx_daily_decision_plan_review_status (user_id, status, outcome_trade_date),
    CONSTRAINT fk_daily_decision_plan_review_plan
        FOREIGN KEY (user_id, decision_plan_id) REFERENCES ai_daily_decision_plan (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
