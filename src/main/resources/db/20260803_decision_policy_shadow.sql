-- DECISION/2.0.0 仅记录 Shadow 结果，不改变当前 ACTIVE 决策策略。
CREATE TABLE IF NOT EXISTS ai_decision_policy_release (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    policy_key VARCHAR(64) NOT NULL,
    version_no VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    config_json MEDIUMTEXT NOT NULL,
    code_checksum VARCHAR(128) NOT NULL,
    shadow_started_at DATETIME(3) NULL,
    activated_at DATETIME(3) NULL,
    retired_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_decision_policy_version (policy_key, version_no),
    active_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_decision_policy_single_active (policy_key, active_guard),
    KEY idx_decision_policy_active (policy_key, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_decision_policy_release ADD COLUMN active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status = ''ACTIVE'' THEN 1 ELSE NULL END) STORED',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'ai_decision_policy_release'
      AND column_name = 'active_guard'
);
PREPARE decision_policy_guard_stmt FROM @ddl;
EXECUTE decision_policy_guard_stmt;
DEALLOCATE PREPARE decision_policy_guard_stmt;

INSERT INTO ai_decision_policy_release (
    policy_key, version_no, status, config_json, code_checksum, shadow_started_at
) VALUES (
    'DECISION', '2.0.0', 'SHADOW',
    '{"version":"DECISION/2.0.0","t1Weight":0.20,"t2Weight":0.30,"t3Weight":0.50,"horizonWeight":0.65,"factorWeight":0.20,"strategyWeight":0.15,"minimumEvaluatedSamples":30,"fullEvaluatedSamples":200,"randomSignal":0.50,"wilsonBaseline":0.50,"wilsonSpan":0.15,"recommendScore":70,"riskLimit":60,"minimumDataQuality":0.90,"stockScopePenalty":1.00,"strategyScopePenalty":0.75,"marketRegimeScopePenalty":0.60,"defaultScopePenalty":0.50}',
    'DECISION_POLICY_SHADOW_2.0.0', CURRENT_TIMESTAMP(3)
)
ON DUPLICATE KEY UPDATE
    status = IF(status = 'ACTIVE', status, 'SHADOW'),
    config_json = IF(status = 'ACTIVE', config_json, VALUES(config_json)),
    code_checksum = IF(status = 'ACTIVE', code_checksum, VALUES(code_checksum)),
    shadow_started_at = COALESCE(shadow_started_at, VALUES(shadow_started_at));

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_decision_policy_release ADD UNIQUE KEY uk_decision_policy_single_active (policy_key, active_guard)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_decision_policy_release'
      AND index_name = 'uk_decision_policy_single_active'
);
PREPARE decision_policy_guard_stmt FROM @ddl;
EXECUTE decision_policy_guard_stmt;
DEALLOCATE PREPARE decision_policy_guard_stmt;

CREATE TABLE IF NOT EXISTS ai_decision_policy_shadow_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    sample_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    active_policy_version VARCHAR(32) NOT NULL,
    shadow_policy_version VARCHAR(32) NOT NULL,
    active_score DECIMAL(10,4) NULL,
    shadow_score DECIMAL(10,4) NULL,
    active_action VARCHAR(16) NULL,
    shadow_action VARCHAR(16) NULL,
    active_risk_score DECIMAL(10,4) NULL,
    shadow_risk_score DECIMAL(10,4) NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    t1_prediction_id BIGINT NULL,
    t2_prediction_id BIGINT NULL,
    t3_prediction_id BIGINT NULL,
    evaluation_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_LABEL',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_decision_policy_shadow_item (user_id, trade_date, sample_id, shadow_policy_version, input_fingerprint),
    KEY idx_decision_policy_shadow_date (trade_date, shadow_policy_version, evaluation_status),
    KEY idx_decision_policy_shadow_stock (user_id, stock_code, trade_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_decision_policy_evaluation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    policy_key VARCHAR(64) NOT NULL,
    active_policy_version VARCHAR(32) NOT NULL,
    shadow_policy_version VARCHAR(32) NOT NULL,
    window_start_date DATE NOT NULL,
    window_end_date DATE NOT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    coverage_rate DECIMAL(10,6) NULL,
    precision_at_k DECIMAL(10,6) NULL,
    brier_score DECIMAL(10,6) NULL,
    calibration_error DECIMAL(10,6) NULL,
    excess_return DECIMAL(14,6) NULL,
    max_drawdown DECIMAL(14,6) NULL,
    turnover_rate DECIMAL(10,6) NULL,
    comparison_json MEDIUMTEXT NOT NULL,
    decision_status VARCHAR(32) NOT NULL DEFAULT 'OBSERVING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_decision_policy_evaluation (policy_key, shadow_policy_version, window_start_date, window_end_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
