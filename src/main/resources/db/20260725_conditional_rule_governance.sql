-- Candidate conditional rules have their own governance lifecycle. They must never
-- reuse model-release Walk-forward/Shadow tables or become ACTIVE automatically.
CREATE TABLE IF NOT EXISTS ai_conditional_rule_experiment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trade_rule_config_id BIGINT NOT NULL,
    experiment_key VARCHAR(160) NOT NULL,
    rule_config_version VARCHAR(64) NOT NULL,
    horizon_days INT NOT NULL,
    window_start_date DATE NOT NULL,
    window_end_date DATE NOT NULL,
    fold_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    candidate_status VARCHAR(32) NOT NULL,
    eligible_sample_count INT NOT NULL DEFAULT 0,
    triggered_sample_count INT NOT NULL DEFAULT 0,
    config_snapshot_json MEDIUMTEXT NOT NULL,
    threshold_snapshot_json MEDIUMTEXT NOT NULL,
    aggregate_metrics_json MEDIUMTEXT NOT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    evaluated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conditional_rule_experiment_key (experiment_key),
    KEY idx_conditional_rule_experiment_config (user_id, trade_rule_config_id, candidate_status, created_at),
    KEY idx_conditional_rule_experiment_window (user_id, horizon_days, window_end_date),
    CONSTRAINT chk_conditional_rule_experiment_horizon CHECK (horizon_days IN (1, 2, 3, 5)),
    CONSTRAINT chk_conditional_rule_experiment_window CHECK (window_start_date <= window_end_date),
    CONSTRAINT fk_conditional_rule_experiment_config
        FOREIGN KEY (user_id, trade_rule_config_id)
        REFERENCES ai_trade_rule_config (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_conditional_rule_experiment_fold (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    experiment_id BIGINT NOT NULL,
    fold_no INT NOT NULL,
    train_start_date DATE NOT NULL,
    train_end_date DATE NOT NULL,
    validation_start_date DATE NOT NULL,
    validation_end_date DATE NOT NULL,
    test_start_date DATE NOT NULL,
    test_end_date DATE NOT NULL,
    train_eligible_count INT NOT NULL DEFAULT 0,
    validation_eligible_count INT NOT NULL DEFAULT 0,
    test_eligible_count INT NOT NULL DEFAULT 0,
    test_triggered_count INT NOT NULL DEFAULT 0,
    metrics_json MEDIUMTEXT NOT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conditional_rule_experiment_fold (experiment_id, fold_no),
    KEY idx_conditional_rule_fold_test (experiment_id, test_start_date, test_end_date),
    CONSTRAINT chk_conditional_rule_fold_dates CHECK (
        train_start_date <= train_end_date
        AND validation_start_date <= validation_end_date
        AND test_start_date <= test_end_date
    ),
    CONSTRAINT fk_conditional_rule_experiment_fold_parent
        FOREIGN KEY (experiment_id) REFERENCES ai_conditional_rule_experiment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_conditional_rule_experiment_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    experiment_id BIGINT NOT NULL,
    experiment_fold_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    sample_label_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    horizon_days INT NOT NULL,
    evaluation_partition VARCHAR(16) NOT NULL,
    rule_code VARCHAR(64) NULL,
    suggested_action VARCHAR(32) NULL,
    triggered TINYINT NOT NULL DEFAULT 0,
    realized_net_return DECIMAL(12,6) NULL,
    realized_excess_return DECIMAL(12,6) NULL,
    action_effective TINYINT NULL,
    feature_fingerprint VARCHAR(128) NOT NULL,
    label_fingerprint VARCHAR(128) NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conditional_rule_experiment_item
        (experiment_id, experiment_fold_id, sample_id, horizon_days),
    KEY idx_conditional_rule_experiment_item_test
        (experiment_id, evaluation_partition, trade_date, triggered),
    KEY idx_conditional_rule_experiment_item_label (sample_label_id),
    CONSTRAINT chk_conditional_rule_experiment_item_horizon CHECK (horizon_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_conditional_rule_experiment_item_parent
        FOREIGN KEY (experiment_id) REFERENCES ai_conditional_rule_experiment (id),
    CONSTRAINT fk_conditional_rule_experiment_item_fold
        FOREIGN KEY (experiment_fold_id) REFERENCES ai_conditional_rule_experiment_fold (id),
    CONSTRAINT fk_conditional_rule_experiment_item_sample
        FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_conditional_rule_experiment_item_label
        FOREIGN KEY (sample_label_id) REFERENCES ai_sample_label (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_conditional_rule_shadow_observation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    experiment_id BIGINT NOT NULL,
    baseline_trade_rule_config_id BIGINT NOT NULL,
    candidate_trade_rule_config_id BIGINT NOT NULL,
    observation_key VARCHAR(160) NOT NULL,
    horizon_days INT NOT NULL,
    window_start_date DATE NOT NULL,
    window_end_date DATE NOT NULL,
    eligible_sample_count INT NOT NULL DEFAULT 0,
    baseline_triggered_count INT NOT NULL DEFAULT 0,
    candidate_triggered_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    metrics_json MEDIUMTEXT NOT NULL,
    threshold_snapshot_json MEDIUMTEXT NOT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conditional_rule_shadow_observation_key (observation_key),
    KEY idx_conditional_rule_shadow_candidate
        (user_id, candidate_trade_rule_config_id, status, window_end_date),
    CONSTRAINT chk_conditional_rule_shadow_horizon CHECK (horizon_days IN (1, 2, 3, 5)),
    CONSTRAINT chk_conditional_rule_shadow_window CHECK (window_start_date <= window_end_date),
    CONSTRAINT chk_conditional_rule_shadow_configs CHECK
        (baseline_trade_rule_config_id <> candidate_trade_rule_config_id),
    CONSTRAINT fk_conditional_rule_shadow_experiment
        FOREIGN KEY (experiment_id) REFERENCES ai_conditional_rule_experiment (id),
    CONSTRAINT fk_conditional_rule_shadow_baseline_config
        FOREIGN KEY (user_id, baseline_trade_rule_config_id)
        REFERENCES ai_trade_rule_config (user_id, id),
    CONSTRAINT fk_conditional_rule_shadow_candidate_config
        FOREIGN KEY (user_id, candidate_trade_rule_config_id)
        REFERENCES ai_trade_rule_config (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_conditional_rule_shadow_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shadow_observation_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    sample_label_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    horizon_days INT NOT NULL,
    baseline_rule_code VARCHAR(64) NULL,
    baseline_action VARCHAR(32) NULL,
    baseline_triggered TINYINT NOT NULL DEFAULT 0,
    candidate_rule_code VARCHAR(64) NULL,
    candidate_action VARCHAR(32) NULL,
    candidate_triggered TINYINT NOT NULL DEFAULT 0,
    realized_net_return DECIMAL(12,6) NULL,
    realized_excess_return DECIMAL(12,6) NULL,
    feature_fingerprint VARCHAR(128) NOT NULL,
    label_fingerprint VARCHAR(128) NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conditional_rule_shadow_item
        (shadow_observation_id, sample_id, horizon_days),
    KEY idx_conditional_rule_shadow_item_triggered
        (shadow_observation_id, candidate_triggered, baseline_triggered),
    CONSTRAINT chk_conditional_rule_shadow_item_horizon CHECK (horizon_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_conditional_rule_shadow_item_parent
        FOREIGN KEY (shadow_observation_id) REFERENCES ai_conditional_rule_shadow_observation (id),
    CONSTRAINT fk_conditional_rule_shadow_item_sample
        FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_conditional_rule_shadow_item_label
        FOREIGN KEY (sample_label_id) REFERENCES ai_sample_label (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_conditional_rule_governance_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trade_rule_config_id BIGINT NOT NULL,
    experiment_id BIGINT NULL,
    shadow_observation_id BIGINT NULL,
    event_key VARCHAR(192) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    decision_status VARCHAR(32) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_user_id BIGINT NULL,
    reason VARCHAR(1024) NOT NULL,
    threshold_snapshot_json MEDIUMTEXT NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conditional_rule_governance_event_key (event_key),
    KEY idx_conditional_rule_governance_config
        (user_id, trade_rule_config_id, occurred_at),
    CONSTRAINT fk_conditional_rule_governance_config
        FOREIGN KEY (user_id, trade_rule_config_id)
        REFERENCES ai_trade_rule_config (user_id, id),
    CONSTRAINT fk_conditional_rule_governance_experiment
        FOREIGN KEY (experiment_id) REFERENCES ai_conditional_rule_experiment (id),
    CONSTRAINT fk_conditional_rule_governance_shadow
        FOREIGN KEY (shadow_observation_id) REFERENCES ai_conditional_rule_shadow_observation (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
