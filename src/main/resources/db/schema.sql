CREATE DATABASE IF NOT EXISTS maogou_stock
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE maogou_stock;

CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NULL,
    email VARCHAR(128) NULL,
    phone VARCHAR(32) NULL,
    password_hash VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    risk_preference VARCHAR(16) NULL,
    last_login_at DATETIME NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_account_username (username),
    UNIQUE KEY uk_user_account_email (email),
    UNIQUE KEY uk_user_account_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS watch_stock (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    market VARCHAR(16) NULL,
    group_name VARCHAR(64) NOT NULL DEFAULT '全部',
    priority INT NOT NULL DEFAULT 100,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_watch_stock_user_code (user_id, stock_code),
    KEY idx_watch_stock_group (user_id, group_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS trade_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    side VARCHAR(16) NOT NULL DEFAULT 'BUY',
    price DECIMAL(18, 4) NOT NULL,
    quantity INT NOT NULL,
    fee DECIMAL(18, 4) NOT NULL DEFAULT 0,
    traded_at DATETIME NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_trade_record_user_time (user_id, traded_at),
    KEY idx_trade_record_stock (user_id, stock_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    api_base_url VARCHAR(512) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    api_key VARCHAR(512) NULL,
    timeout_ms INT NOT NULL DEFAULT 60000,
    temperature DECIMAL(4, 2) NOT NULL DEFAULT 0.20,
    max_tokens INT NOT NULL DEFAULT 2048,
    intraday_interval_minutes INT NOT NULL DEFAULT 30,
    close_analysis_time VARCHAR(16) NOT NULL DEFAULT '15:30',
    analysis_scope VARCHAR(64) NOT NULL DEFAULT '全部自选股',
    prompt_template TEXT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_model_config_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_prompt_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_prompt_template_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_analysis_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    score INT NULL,
    advice VARCHAR(64) NULL,
    technical_analysis TEXT NULL,
    risk_warning TEXT NULL,
    buy_sell_points TEXT NULL,
    prompt_summary TEXT NULL,
    raw_prompt MEDIUMTEXT NULL,
    raw_response MEDIUMTEXT NULL,
    source_model VARCHAR(128) NULL,
    prompt_template_id BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message TEXT NULL,
    report_date DATE NOT NULL,
    generated_at DATETIME NOT NULL,
    sample_id BIGINT NULL,
    prediction_id BIGINT NULL,
    strategy_version_id BIGINT NULL,
    data_quality_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    calibrated_confidence DECIMAL(10, 4) NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_report_user_time (user_id, generated_at),
    KEY idx_ai_report_stock_time (stock_code, generated_at),
    KEY idx_ai_report_sample (sample_id),
    KEY idx_ai_report_prediction (prediction_id),
    UNIQUE KEY uk_ai_report_daily_source (user_id, stock_code, report_date, source_model, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_analysis_outcome (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    report_date DATE NOT NULL,
    horizon_days INT NOT NULL,
    prediction_direction VARCHAR(16) NOT NULL,
    actual_direction VARCHAR(16) NOT NULL,
    entry_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    close_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    high_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    low_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    pct_change DECIMAL(10, 4) NOT NULL DEFAULT 0,
    max_drawdown DECIMAL(10, 4) NOT NULL DEFAULT 0,
    direction_correct TINYINT NOT NULL DEFAULT 0,
    success TINYINT NOT NULL DEFAULT 0,
    success_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    evaluated_at DATETIME NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_outcome_report_horizon (user_id, report_id, horizon_days, deleted),
    KEY idx_ai_outcome_user_eval (user_id, evaluated_at),
    KEY idx_ai_outcome_stock_date (stock_code, report_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_analysis_decision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    decision VARCHAR(16) NOT NULL DEFAULT 'WATCH',
    confidence DECIMAL(8, 4) NOT NULL DEFAULT 0,
    holding_period VARCHAR(32) NOT NULL DEFAULT '1-3d',
    target_direction VARCHAR(16) NOT NULL DEFAULT 'SIDEWAYS',
    risk_level VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    summary VARCHAR(1024) NULL,
    factors_json MEDIUMTEXT NULL,
    raw_decision_json MEDIUMTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_decision_user_report (user_id, report_id),
    KEY idx_ai_decision_stock (user_id, stock_code),
    KEY idx_ai_decision_direction (user_id, target_direction)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_analysis_factor_hit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    outcome_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    factor_code VARCHAR(64) NOT NULL,
    factor_name VARCHAR(128) NOT NULL,
    factor_group VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    weight_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    reason VARCHAR(512) NULL,
    success_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    pct_change DECIMAL(10, 4) NOT NULL DEFAULT 0,
    market_regime VARCHAR(32) NOT NULL DEFAULT '未知',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_factor_hit_user_factor (user_id, factor_code),
    KEY idx_factor_hit_outcome (outcome_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_factor_stat (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    factor_code VARCHAR(64) NOT NULL,
    factor_name VARCHAR(128) NOT NULL,
    factor_group VARCHAR(32) NOT NULL,
    market_regime VARCHAR(32) NOT NULL DEFAULT '未知',
    sample_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    success_rate DECIMAL(10, 4) NOT NULL DEFAULT 0,
    avg_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    avg_drawdown DECIMAL(10, 4) NOT NULL DEFAULT 0,
    weight_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    last_evaluated_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_factor_stat_user_factor_regime (user_id, factor_code, market_regime),
    KEY idx_factor_stat_user_weight (user_id, weight_score)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_strategy_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    strategy_summary TEXT NULL,
    factor_snapshot MEDIUMTEXT NULL,
    prompt_template MEDIUMTEXT NULL,
    avg_success_rate DECIMAL(10, 4) NOT NULL DEFAULT 0,
    avg_return DECIMAL(10, 4) NOT NULL DEFAULT 0,
    max_drawdown DECIMAL(10, 4) NOT NULL DEFAULT 0,
    sample_count INT NOT NULL DEFAULT 0,
    active TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_strategy_version_user_no (user_id, version_no),
    KEY idx_strategy_version_user_active (user_id, active)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_strategy_evolution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    strategy_version_id BIGINT NULL,
    action_type VARCHAR(32) NOT NULL,
    action_summary VARCHAR(512) NULL,
    before_snapshot MEDIUMTEXT NULL,
    after_snapshot MEDIUMTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_strategy_log_user_time (user_id, created_at),
    KEY idx_strategy_log_version (strategy_version_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_chat_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    model_name VARCHAR(128) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_chat_session_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    message_role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    model_name VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    error_message TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ai_chat_message_session_time (session_id, created_at),
    KEY idx_ai_chat_message_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_user_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    memory_summary TEXT NULL,
    last_interaction_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_user_memory_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS news_flash (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(512) NOT NULL,
    source VARCHAR(64) NULL,
    url VARCHAR(1024) NULL,
    published_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_news_flash_published (published_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS market_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(64) NULL,
    market VARCHAR(16) NULL,
    latest_price DECIMAL(18, 4) NOT NULL,
    change_amount DECIMAL(18, 4) NOT NULL DEFAULT 0,
    change_percent DECIMAL(10, 4) NOT NULL DEFAULT 0,
    volume_ratio DECIMAL(10, 4) NULL,
    amount DECIMAL(24, 4) NULL,
    quote_time DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_market_snapshot_symbol_time (symbol, quote_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS stock_kline (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(16) NOT NULL,
    period VARCHAR(16) NOT NULL DEFAULT 'day',
    trade_date DATE NOT NULL,
    open_price DECIMAL(18, 4) NOT NULL,
    close_price DECIMAL(18, 4) NOT NULL,
    low_price DECIMAL(18, 4) NOT NULL,
    high_price DECIMAL(18, 4) NOT NULL,
    volume BIGINT NULL,
    amount DECIMAL(24, 4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_kline_code_period_date (stock_code, period, trade_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

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

INSERT IGNORE INTO user_account (id, username, display_name, email, status)
VALUES (1, 'demo', '默认用户', NULL, 'ACTIVE');
