-- AI learning system v1 for 猫狗智投.
-- Adds sample, factor, prediction, label, experiment, backtest and eval tables.

DROP PROCEDURE IF EXISTS maogou_add_column_if_missing;
DROP PROCEDURE IF EXISTS maogou_add_index_if_missing;

DELIMITER //
CREATE PROCEDURE maogou_add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

CREATE PROCEDURE maogou_add_index_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_index_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND INDEX_NAME = p_index_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

CALL maogou_add_column_if_missing('ai_analysis_report', 'sample_id', 'BIGINT NULL');
CALL maogou_add_column_if_missing('ai_analysis_report', 'prediction_id', 'BIGINT NULL');
CALL maogou_add_column_if_missing('ai_analysis_report', 'strategy_version_id', 'BIGINT NULL');
CALL maogou_add_column_if_missing('ai_analysis_report', 'data_quality_score', 'DECIMAL(10, 4) NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_analysis_report', 'calibrated_confidence', 'DECIMAL(10, 4) NOT NULL DEFAULT 0');
CALL maogou_add_index_if_missing('ai_analysis_report', 'idx_ai_report_sample', 'KEY `idx_ai_report_sample` (`sample_id`)');
CALL maogou_add_index_if_missing('ai_analysis_report', 'idx_ai_report_prediction', 'KEY `idx_ai_report_prediction` (`prediction_id`)');

DROP PROCEDURE IF EXISTS maogou_add_column_if_missing;
DROP PROCEDURE IF EXISTS maogou_add_index_if_missing;

CREATE TABLE IF NOT EXISTS ai_stock_universe (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    universe_code VARCHAR(64) NOT NULL,
    universe_name VARCHAR(128) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    filters_json MEDIUMTEXT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_universe_user_code (user_id, universe_code),
    KEY idx_ai_universe_user_active (user_id, active)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_prediction_sample (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    sample_time DATETIME NOT NULL,
    trade_date DATE NOT NULL,
    sample_phase VARCHAR(32) NOT NULL,
    universe_code VARCHAR(64) NOT NULL DEFAULT 'WATCHLIST',
    market_regime VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    sector_code VARCHAR(32) NULL,
    sector_name VARCHAR(64) NULL,
    data_quality_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    tradable TINYINT NOT NULL DEFAULT 1,
    exclude_reason VARCHAR(255) NULL,
    feature_snapshot MEDIUMTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_sample_user_stock_phase (user_id, stock_code, trade_date, sample_phase, universe_code),
    KEY idx_ai_sample_user_time (user_id, sample_time),
    KEY idx_ai_sample_stock_date (stock_code, trade_date),
    KEY idx_ai_sample_regime (user_id, market_regime)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_factor_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    factor_code VARCHAR(64) NOT NULL,
    factor_name VARCHAR(128) NOT NULL,
    factor_group VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL DEFAULT 'NEUTRAL',
    formula_desc TEXT NULL,
    required_fields_json MEDIUMTEXT NULL,
    default_weight DECIMAL(10, 4) NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    version_no VARCHAR(32) NOT NULL DEFAULT 'v1',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_factor_definition_code_version (factor_code, version_no),
    KEY idx_factor_definition_group (factor_group, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_factor_value (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    factor_code VARCHAR(64) NOT NULL,
    factor_value DECIMAL(18, 6) NULL,
    normalized_value DECIMAL(18, 6) NULL,
    hit TINYINT NOT NULL DEFAULT 0,
    direction VARCHAR(16) NOT NULL DEFAULT 'NEUTRAL',
    evidence VARCHAR(512) NULL,
    calculated_at DATETIME NOT NULL,
    UNIQUE KEY uk_factor_value_sample_factor (sample_id, factor_code),
    KEY idx_factor_value_sample (sample_id),
    KEY idx_factor_value_user_factor (user_id, factor_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_prediction_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    report_id BIGINT NULL,
    strategy_version_id BIGINT NOT NULL DEFAULT 0,
    model_version_id BIGINT NOT NULL DEFAULT 0,
    prompt_template_id BIGINT NOT NULL DEFAULT 0,
    action VARCHAR(16) NOT NULL,
    target_direction VARCHAR(16) NOT NULL,
    horizon_days INT NOT NULL DEFAULT 3,
    confidence DECIMAL(10, 4) NOT NULL DEFAULT 0,
    score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    rank_no INT NULL,
    risk_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    reason_json MEDIUMTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prediction_user_sample_strategy (user_id, sample_id, strategy_version_id, horizon_days),
    KEY idx_prediction_user_sample (user_id, sample_id),
    KEY idx_prediction_strategy_time (strategy_version_id, created_at),
    KEY idx_prediction_rank (user_id, horizon_days, rank_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_prediction_label (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    prediction_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    horizon_days INT NOT NULL,
    entry_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    exit_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    close_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    max_favorable_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    max_adverse_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    benchmark_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    sector_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    excess_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    net_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    hit_direction TINYINT NOT NULL DEFAULT 0,
    hit_target TINYINT NOT NULL DEFAULT 0,
    hit_stop_loss TINYINT NOT NULL DEFAULT 0,
    tradable TINYINT NOT NULL DEFAULT 1,
    label_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    label_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    evaluated_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prediction_label_horizon (user_id, prediction_id, horizon_days),
    KEY idx_prediction_label_stock (stock_code, evaluated_at),
    KEY idx_prediction_label_sample (sample_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_strategy_experiment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    universe_code VARCHAR(64) NOT NULL DEFAULT 'WATCHLIST',
    train_start_date DATE NULL,
    train_end_date DATE NULL,
    validation_start_date DATE NULL,
    validation_end_date DATE NULL,
    test_start_date DATE NULL,
    test_end_date DATE NULL,
    config_json MEDIUMTEXT NULL,
    metrics_json MEDIUMTEXT NULL,
    baseline_metrics_json MEDIUMTEXT NULL,
    can_promote TINYINT NOT NULL DEFAULT 0,
    promoted_strategy_version_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_experiment_user_time (user_id, created_at),
    KEY idx_ai_experiment_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_backtest_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    strategy_version_id BIGINT NOT NULL DEFAULT 0,
    experiment_id BIGINT NULL,
    title VARCHAR(128) NOT NULL,
    universe_code VARCHAR(64) NOT NULL DEFAULT 'WATCHLIST',
    horizon_days INT NOT NULL DEFAULT 3,
    top_k INT NOT NULL DEFAULT 5,
    start_date DATE NULL,
    end_date DATE NULL,
    total_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    win_rate DECIMAL(10, 4) NOT NULL DEFAULT 0,
    avg_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    max_drawdown DECIMAL(10, 4) NOT NULL DEFAULT 0,
    benchmark_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    trade_count INT NOT NULL DEFAULT 0,
    metrics_json MEDIUMTEXT NULL,
    equity_curve_json MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_backtest_user_time (user_id, created_at),
    KEY idx_backtest_strategy (strategy_version_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_backtest_trade (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    backtest_run_id BIGINT NOT NULL,
    prediction_id BIGINT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    entry_date DATE NOT NULL,
    exit_date DATE NOT NULL,
    entry_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    exit_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    net_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    max_drawdown DECIMAL(10, 4) NOT NULL DEFAULT 0,
    rank_no INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_backtest_trade_run (backtest_run_id),
    KEY idx_backtest_trade_stock (stock_code, entry_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_learning_job_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL DEFAULT 0,
    job_name VARCHAR(128) NOT NULL,
    job_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    processed_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_learning_job_time (job_type, started_at),
    KEY idx_learning_job_user (user_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_eval_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL DEFAULT 'openai-compatible',
    prompt_template_id BIGINT NOT NULL DEFAULT 0,
    eval_type VARCHAR(64) NOT NULL DEFAULT 'REPORT_JSON',
    json_success_rate DECIMAL(10, 4) NOT NULL DEFAULT 0,
    avg_latency_ms DECIMAL(18, 4) NOT NULL DEFAULT 0,
    sample_count INT NOT NULL DEFAULT 0,
    score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    metrics_json MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_model_eval_user_time (user_id, created_at),
    KEY idx_model_eval_model (model_name, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
