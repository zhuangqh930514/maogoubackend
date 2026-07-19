-- 猫狗智投 AI 研究统一数据域一次性重置迁移 v1.1。
-- 该迁移会清空旧 AI 研究历史，但保留账号、自选股、持仓、模型配置、提示词、聊天和用户记忆。
-- MySQL DDL 会隐式提交；若版本状态停在 APPLYING，必须从切换点备份恢复后重新执行。

CREATE TABLE IF NOT EXISTS ai_research_schema_version (
    version_no VARCHAR(64) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    schema_checksum VARCHAR(128) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO ai_research_schema_version
    (version_no, status, started_at, schema_checksum)
VALUES
    ('20260714-unified-1.1', 'APPLYING', CURRENT_TIMESTAMP(3), 'AI_RESEARCH_UNIFIED_1_1');

ALTER TABLE user_account
    ADD COLUMN system_role VARCHAR(16) NOT NULL DEFAULT 'USER' AFTER status,
    ADD CONSTRAINT chk_user_account_system_role
        CHECK (system_role IN ('USER', 'OPERATOR', 'ADMIN'));

CREATE TEMPORARY TABLE tmp_ai_trading_calendar_backup LIKE ai_trading_calendar;
INSERT INTO tmp_ai_trading_calendar_backup SELECT * FROM ai_trading_calendar;
SET @ai_calendar_backup_count = (SELECT COUNT(*) FROM tmp_ai_trading_calendar_backup);
SET @ai_calendar_backup_min_date = (
    SELECT COALESCE(MIN(trade_date), '1900-01-01') FROM tmp_ai_trading_calendar_backup
);
SET @ai_calendar_backup_max_date = (
    SELECT COALESCE(MAX(trade_date), '1900-01-01') FROM tmp_ai_trading_calendar_backup
);
-- 旧环境可能尚未积累正式交易日历。空表也必须原样迁移，禁止用工作日近似值伪造交易日；
-- 首次成熟标签验证会根据带来源指纹的基准指数 K 线写入正式日历。

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS ai_analysis_report;
DROP TABLE IF EXISTS ai_trade_rule_config;
DROP TABLE IF EXISTS ai_trade_plan_review;
DROP TABLE IF EXISTS ai_trade_rule_performance;
DROP TABLE IF EXISTS ai_analysis_outcome;
DROP TABLE IF EXISTS ai_analysis_decision;
DROP TABLE IF EXISTS ai_analysis_factor_hit;
DROP TABLE IF EXISTS ai_factor_stat;
DROP TABLE IF EXISTS ai_strategy_version;
DROP TABLE IF EXISTS ai_strategy_evolution_log;
DROP TABLE IF EXISTS ai_stock_universe;
DROP TABLE IF EXISTS ai_prediction_sample;
DROP TABLE IF EXISTS ai_factor_definition;
DROP TABLE IF EXISTS ai_factor_value;
DROP TABLE IF EXISTS ai_prediction_result;
DROP TABLE IF EXISTS ai_prediction_label;
DROP TABLE IF EXISTS ai_strategy_experiment;
DROP TABLE IF EXISTS ai_backtest_run;
DROP TABLE IF EXISTS ai_backtest_trade;
DROP TABLE IF EXISTS ai_learning_job_log;
DROP TABLE IF EXISTS ai_model_eval_run;
DROP TABLE IF EXISTS ai_daily_insight_snapshot;
DROP TABLE IF EXISTS ai_daily_insight_item;
DROP TABLE IF EXISTS ai_data_batch;
DROP TABLE IF EXISTS ai_sample_v2;
DROP TABLE IF EXISTS ai_factor_value_v2;
DROP TABLE IF EXISTS ai_trading_calendar;
DROP TABLE IF EXISTS ai_training_dataset;
DROP TABLE IF EXISTS ai_model_version;
DROP TABLE IF EXISTS ai_strategy_release;
DROP TABLE IF EXISTS ai_prediction_v2;
DROP TABLE IF EXISTS ai_label_v2;
DROP TABLE IF EXISTS ai_label_cost_evidence;
DROP TABLE IF EXISTS ai_training_dataset_item;
DROP TABLE IF EXISTS ai_factor_performance_v2;
DROP TABLE IF EXISTS ai_walk_forward_run;
DROP TABLE IF EXISTS ai_walk_forward_fold;
DROP TABLE IF EXISTS ai_walk_forward_baseline;
DROP TABLE IF EXISTS ai_portfolio_backtest_run;
DROP TABLE IF EXISTS ai_portfolio_backtest_daily;
DROP TABLE IF EXISTS ai_portfolio_backtest_trade;
DROP TABLE IF EXISTS ai_portfolio_backtest_position;
DROP TABLE IF EXISTS ai_pipeline_run;
DROP TABLE IF EXISTS ai_pipeline_step;
DROP TABLE IF EXISTS ai_shadow_evaluation;
DROP TABLE IF EXISTS ai_shadow_evaluation_item;
DROP TABLE IF EXISTS ai_drift_event;
DROP TABLE IF EXISTS ai_strategy_governance_event;
DROP TABLE IF EXISTS ai_research_daily_report;
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE IF NOT EXISTS ai_research_universe (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    universe_code VARCHAR(64) NOT NULL,
    universe_name VARCHAR(128) NOT NULL,
    market_code VARCHAR(16) NOT NULL DEFAULT 'CN_A',
    selection_policy_json MEDIUMTEXT NOT NULL,
    minimum_stock_count INT NOT NULL DEFAULT 200,
    enabled TINYINT NOT NULL DEFAULT 1,
    seed_version VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_research_universe_code (universe_code),
    KEY idx_research_universe_enabled (enabled, market_code),
    CONSTRAINT chk_research_universe_enabled CHECK (enabled IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_research_universe_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    research_universe_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    as_of_time DATETIME(3) NOT NULL,
    universe_version VARCHAR(64) NOT NULL,
    calendar_version VARCHAR(64) NOT NULL,
    membership_source_name VARCHAR(64) NULL,
    membership_source_revision VARCHAR(64) NULL,
    source_observed_at DATETIME(3) NULL,
    point_in_time_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    point_in_time_reason VARCHAR(255) NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    item_count INT NOT NULL DEFAULT 0,
    quality_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    status VARCHAR(32) NOT NULL DEFAULT 'BUILDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_universe_snapshot_version
        (research_universe_id, trade_date, universe_version),
    UNIQUE KEY uk_universe_snapshot_fingerprint
        (research_universe_id, source_fingerprint),
    KEY idx_universe_snapshot_trade (trade_date, status, quality_status),
    KEY idx_universe_snapshot_point_in_time
        (trade_date, point_in_time_status, status, quality_status),
    CONSTRAINT fk_universe_snapshot_universe
        FOREIGN KEY (research_universe_id) REFERENCES ai_research_universe (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_research_universe_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    universe_snapshot_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    market VARCHAR(16) NULL,
    sector_code VARCHAR(32) NULL,
    sector_name VARCHAR(64) NULL,
    industry_code VARCHAR(32) NULL,
    industry_name VARCHAR(64) NULL,
    industry_standard VARCHAR(32) NULL,
    listed_status VARCHAR(32) NOT NULL DEFAULT 'LISTED',
    source_type VARCHAR(96) NOT NULL,
    included TINYINT NOT NULL DEFAULT 1,
    inclusion_reason VARCHAR(255) NULL,
    exclude_reason VARCHAR(255) NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_universe_item_stock (universe_snapshot_id, stock_code),
    KEY idx_universe_item_stock_snapshot (stock_code, universe_snapshot_id),
    KEY idx_universe_item_sector (universe_snapshot_id, sector_code, included),
    KEY idx_universe_item_effective (stock_code, effective_from, effective_to, included),
    CONSTRAINT chk_universe_item_included CHECK (included IN (0, 1)),
    CONSTRAINT fk_universe_item_snapshot
        FOREIGN KEY (universe_snapshot_id) REFERENCES ai_research_universe_snapshot (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_data_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    universe_snapshot_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    sample_phase VARCHAR(32) NOT NULL,
    as_of_time DATETIME(3) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    source_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    quote_as_of DATETIME(3) NULL,
    kline_as_of DATE NULL,
    benchmark_as_of DATE NULL,
    sector_as_of DATETIME(3) NULL,
    finance_as_of DATETIME(3) NULL,
    news_as_of DATETIME(3) NULL,
    quality_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    quality_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    item_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    error_detail MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_data_batch_idempotency (idempotency_key),
    KEY idx_data_batch_trade_phase (trade_date, sample_phase, status),
    KEY idx_data_batch_universe (universe_snapshot_id, as_of_time),
    CONSTRAINT fk_data_batch_universe_snapshot
        FOREIGN KEY (universe_snapshot_id) REFERENCES ai_research_universe_snapshot (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_source_observation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_batch_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NULL,
    source_type VARCHAR(32) NOT NULL,
    provider_code VARCHAR(32) NOT NULL,
    endpoint_type VARCHAR(64) NOT NULL,
    event_time DATETIME(3) NULL,
    published_at DATETIME(3) NULL,
    first_seen_at DATETIME(3) NOT NULL,
    fetched_at DATETIME(3) NOT NULL,
    as_of_time DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    source_revision VARCHAR(64) NOT NULL,
    source_uri VARCHAR(1024) NULL,
    payload_json MEDIUMTEXT NULL,
    payload_checksum VARCHAR(128) NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    freshness_status VARCHAR(32) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    missing_reason VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_source_observation_fingerprint (source_fingerprint),
    KEY idx_source_batch_type (data_batch_id, source_type, quality_status),
    KEY idx_source_stock_type_event (stock_code, source_type, event_time, published_at),
    KEY idx_source_stock_type_quality_asof (stock_code, source_type, quality_status, as_of_time, id),
    KEY idx_source_available (available_at, provider_code, endpoint_type),
    KEY idx_source_visibility (as_of_time, published_at, quality_status),
    CONSTRAINT chk_source_observation_timeline CHECK (fetched_at >= first_seen_at),
    CONSTRAINT fk_source_observation_batch
        FOREIGN KEY (data_batch_id) REFERENCES ai_data_batch (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_security_daily_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stock_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    source_batch_id BIGINT NULL,
    source_revision VARCHAR(64) NOT NULL,
    revision_no INT NOT NULL DEFAULT 1,
    is_current TINYINT NOT NULL DEFAULT 1,
    supersedes_state_id BIGINT NULL,
    listed_on DATE NULL,
    listed_days INT NULL,
    security_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    st_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    is_st TINYINT NULL,
    suspended TINYINT NULL,
    limit_ratio DECIMAL(10, 6) NULL,
    limit_up_price DECIMAL(18, 4) NULL,
    limit_down_price DECIMAL(18, 4) NULL,
    is_limit_up TINYINT NULL,
    is_limit_down TINYINT NULL,
    buy_tradable TINYINT NULL,
    sell_tradable TINYINT NULL,
    quality_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    missing_reason VARCHAR(255) NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    current_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN is_current = 1 THEN 1 ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_security_daily_state_revision (stock_code, trade_date, revision_no),
    UNIQUE KEY uk_security_daily_state_current (stock_code, trade_date, current_guard),
    UNIQUE KEY uk_security_daily_state_fingerprint (source_fingerprint),
    KEY idx_security_daily_state_current_date (trade_date, is_current, quality_status),
    KEY idx_security_daily_state_stock_date (stock_code, trade_date, is_current),
    CONSTRAINT fk_security_daily_state_supersedes
        FOREIGN KEY (supersedes_state_id) REFERENCES ai_security_daily_state (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_industry_daily_bar (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    industry_code VARCHAR(32) NOT NULL,
    industry_name VARCHAR(128) NOT NULL,
    classification_standard VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    open_price DECIMAL(20, 6) NOT NULL,
    high_price DECIMAL(20, 6) NOT NULL,
    low_price DECIMAL(20, 6) NOT NULL,
    close_price DECIMAL(20, 6) NOT NULL,
    volume DECIMAL(24, 6) NOT NULL DEFAULT 0,
    amount DECIMAL(24, 6) NOT NULL DEFAULT 0,
    source_name VARCHAR(32) NOT NULL,
    source_revision VARCHAR(64) NOT NULL,
    revision_no INT NOT NULL DEFAULT 1,
    is_current TINYINT NOT NULL DEFAULT 1,
    supersedes_bar_id BIGINT NULL,
    quality_status VARCHAR(32) NOT NULL,
    source_ref VARCHAR(255) NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    current_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN is_current = 1 THEN 1 ELSE NULL END
    ) STORED,
    UNIQUE KEY uk_industry_bar_revision
        (industry_code, classification_standard, trade_date, revision_no),
    UNIQUE KEY uk_industry_bar_current
        (industry_code, classification_standard, trade_date, current_guard),
    UNIQUE KEY uk_industry_bar_fingerprint (source_fingerprint),
    KEY idx_industry_bar_series
        (industry_code, classification_standard, trade_date, is_current, quality_status),
    KEY idx_industry_bar_observed (observed_at, source_name, source_revision),
    CONSTRAINT fk_industry_bar_supersedes
        FOREIGN KEY (supersedes_bar_id) REFERENCES ai_industry_daily_bar (id),
    CONSTRAINT chk_industry_bar_prices CHECK (
        open_price > 0 AND high_price > 0 AND low_price > 0 AND close_price > 0
        AND high_price >= open_price AND high_price >= close_price AND high_price >= low_price
        AND low_price <= open_price AND low_price <= close_price
    ),
    CONSTRAINT chk_industry_bar_nonnegative CHECK (volume >= 0 AND amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_source_health (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_code VARCHAR(32) NOT NULL,
    endpoint_type VARCHAR(64) NOT NULL,
    source_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    last_attempt_at DATETIME(3) NULL,
    last_success_at DATETIME(3) NULL,
    consecutive_failure_count INT NOT NULL DEFAULT 0,
    cooldown_until DATETIME(3) NULL,
    last_error_message VARCHAR(1024) NULL,
    last_response_fingerprint VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_source_health_provider (provider_code, endpoint_type),
    KEY idx_source_health_status (source_status, cooldown_until)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_sample (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_batch_id BIGINT NOT NULL,
    universe_item_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    trade_date DATE NOT NULL,
    sample_phase VARCHAR(32) NOT NULL,
    as_of_time DATETIME(3) NOT NULL,
    market_regime VARCHAR(32) NOT NULL DEFAULT 'UNCLASSIFIED',
    sector_code VARCHAR(32) NULL,
    sector_name VARCHAR(64) NULL,
    data_quality_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    quality_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    tradable_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    exclude_reason VARCHAR(255) NULL,
    feature_version VARCHAR(64) NOT NULL,
    feature_snapshot MEDIUMTEXT NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_sample_snapshot
        (stock_code, as_of_time, sample_phase, feature_version, source_fingerprint),
    KEY idx_sample_batch (data_batch_id),
    KEY idx_sample_trade_stock (trade_date, stock_code, quality_status),
    KEY idx_sample_stock_time (stock_code, as_of_time),
    KEY idx_sample_universe_item (universe_item_id),
    CONSTRAINT fk_sample_batch FOREIGN KEY (data_batch_id) REFERENCES ai_data_batch (id),
    CONSTRAINT fk_sample_universe_item
        FOREIGN KEY (universe_item_id) REFERENCES ai_research_universe_item (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_factor_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    factor_code VARCHAR(64) NOT NULL,
    factor_version VARCHAR(32) NOT NULL,
    factor_name VARCHAR(128) NOT NULL,
    factor_group VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL DEFAULT 'NEUTRAL',
    formula_desc VARCHAR(1024) NOT NULL,
    required_fields_json MEDIUMTEXT NOT NULL,
    default_weight DECIMAL(10, 4) NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    seed_version VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_factor_definition_version (factor_code, factor_version),
    KEY idx_factor_definition_enabled (enabled, factor_group, factor_code),
    CONSTRAINT chk_factor_definition_enabled CHECK (enabled IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_factor_value (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sample_id BIGINT NOT NULL,
    factor_definition_id BIGINT NOT NULL,
    raw_value DECIMAL(24, 8) NULL,
    normalized_value DECIMAL(18, 8) NULL,
    hit TINYINT NOT NULL DEFAULT 0,
    missing TINYINT NOT NULL DEFAULT 0,
    missing_reason VARCHAR(255) NULL,
    evidence_json MEDIUMTEXT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    calculated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_factor_value_sample_definition (sample_id, factor_definition_id),
    KEY idx_factor_value_definition (factor_definition_id, calculated_at),
    KEY idx_factor_value_missing (missing, factor_definition_id, calculated_at),
    CONSTRAINT chk_factor_value_flags CHECK (hit IN (0, 1) AND missing IN (0, 1)),
    CONSTRAINT fk_factor_value_sample FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_factor_value_definition
        FOREIGN KEY (factor_definition_id) REFERENCES ai_factor_definition (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_trading_calendar (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    calendar_version VARCHAR(64) NOT NULL,
    is_trade_day TINYINT NOT NULL,
    session_open_time TIME NULL,
    session_close_time TIME NULL,
    previous_trade_date DATE NULL,
    next_trade_date DATE NULL,
    source_name VARCHAR(64) NOT NULL,
    source_as_of DATETIME(3) NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trading_calendar_version (market_code, trade_date, calendar_version),
    KEY idx_trading_calendar_lookup (market_code, calendar_version, is_trade_day, trade_date),
    KEY idx_trading_calendar_source (source_fingerprint),
    CONSTRAINT chk_trading_calendar_day CHECK (is_trade_day IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO ai_trading_calendar
    (id, market_code, trade_date, calendar_version, is_trade_day, session_open_time,
     session_close_time, previous_trade_date, next_trade_date, source_name, source_as_of,
     source_fingerprint, created_at)
SELECT
    id, market_code, trade_date, calendar_version, is_trade_day, session_open_time,
    session_close_time, previous_trade_date, next_trade_date, source_name, source_as_of,
    source_fingerprint, created_at
FROM tmp_ai_trading_calendar_backup;

CREATE TEMPORARY TABLE tmp_ai_calendar_restore_guard (
    id TINYINT PRIMARY KEY,
    restore_ok TINYINT NOT NULL,
    CONSTRAINT chk_tmp_ai_calendar_restore CHECK (restore_ok = 1)
);
INSERT INTO tmp_ai_calendar_restore_guard (id, restore_ok)
SELECT 1, CASE WHEN
    (SELECT COUNT(*) FROM ai_trading_calendar) = @ai_calendar_backup_count
    AND (SELECT COALESCE(MIN(trade_date), '1900-01-01') FROM ai_trading_calendar) =
        @ai_calendar_backup_min_date
    AND (SELECT COALESCE(MAX(trade_date), '1900-01-01') FROM ai_trading_calendar) =
        @ai_calendar_backup_max_date
THEN 1 ELSE 0 END;

CREATE TABLE IF NOT EXISTS ai_training_dataset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    research_universe_id BIGINT NOT NULL,
    dataset_key VARCHAR(96) NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    model_family VARCHAR(64) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    label_version VARCHAR(32) NOT NULL,
    calendar_version VARCHAR(64) NOT NULL,
    as_of_time DATETIME(3) NOT NULL,
    train_start_date DATE NOT NULL,
    train_end_date DATE NOT NULL,
    validation_start_date DATE NOT NULL,
    validation_end_date DATE NOT NULL,
    test_start_date DATE NOT NULL,
    test_end_date DATE NOT NULL,
    max_horizon_days INT NOT NULL,
    purge_trading_days INT NOT NULL DEFAULT 5,
    embargo_trading_days INT NOT NULL DEFAULT 5,
    source_query_json MEDIUMTEXT NOT NULL,
    selection_policy_json MEDIUMTEXT NOT NULL,
    lineage_fingerprint VARCHAR(128) NOT NULL,
    artifact_uri VARCHAR(512) NULL,
    artifact_checksum VARCHAR(128) NULL,
    row_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'BUILDING',
    finalized_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_training_dataset_version (dataset_key, version_no),
    UNIQUE KEY uk_training_dataset_lineage (lineage_fingerprint),
    KEY idx_training_dataset_status (model_family, status, as_of_time),
    KEY idx_training_dataset_universe (research_universe_id, as_of_time),
    CONSTRAINT chk_training_dataset_dates CHECK (
        train_start_date <= train_end_date
        AND train_end_date < validation_start_date
        AND validation_start_date <= validation_end_date
        AND validation_end_date < test_start_date
        AND test_start_date <= test_end_date
    ),
    CONSTRAINT fk_training_dataset_universe
        FOREIGN KEY (research_universe_id) REFERENCES ai_research_universe (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    training_dataset_id BIGINT NOT NULL,
    model_family VARCHAR(64) NOT NULL,
    model_key VARCHAR(64) NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    model_type VARCHAR(32) NOT NULL,
    algorithm VARCHAR(64) NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    trainer_version VARCHAR(64) NOT NULL,
    random_seed BIGINT NOT NULL,
    artifact_uri VARCHAR(512) NULL,
    artifact_checksum VARCHAR(128) NULL,
    feature_manifest_uri VARCHAR(512) NULL,
    feature_manifest_checksum VARCHAR(128) NULL,
    train_start_date DATE NULL,
    train_end_date DATE NULL,
    validation_start_date DATE NULL,
    validation_end_date DATE NULL,
    test_start_date DATE NULL,
    test_end_date DATE NULL,
    parameters_json MEDIUMTEXT NULL,
    metrics_json MEDIUMTEXT NULL,
    calibration_json MEDIUMTEXT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_model_version_key_no (model_family, model_key, version_no),
    KEY idx_model_version_status (model_family, status, created_at),
    KEY idx_model_version_dataset (training_dataset_id, created_at),
    CONSTRAINT fk_model_version_dataset
        FOREIGN KEY (training_dataset_id) REFERENCES ai_training_dataset (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_strategy_release (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    research_universe_id BIGINT NOT NULL,
    model_family VARCHAR(64) NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    model_version_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    release_role VARCHAR(32) NOT NULL DEFAULT 'CHALLENGER',
    active_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN release_role = 'CHAMPION' AND status = 'ACTIVE' THEN 1 ELSE NULL END
    ) STORED,
    config_json MEDIUMTEXT NOT NULL,
    factor_snapshot_json MEDIUMTEXT NULL,
    validation_metrics_json MEDIUMTEXT NULL,
    promotion_reason TEXT NULL,
    rollback_reason TEXT NULL,
    seed_version VARCHAR(64) NULL,
    shadow_started_at DATETIME(3) NULL,
    shadow_ended_at DATETIME(3) NULL,
    activated_at DATETIME(3) NULL,
    retired_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_strategy_release_version
        (model_family, research_universe_id, version_no),
    UNIQUE KEY uk_active_strategy_release
        (model_family, research_universe_id, active_guard),
    KEY idx_strategy_release_role
        (model_family, research_universe_id, release_role, status),
    KEY idx_strategy_release_model (model_version_id),
    CONSTRAINT fk_strategy_release_universe
        FOREIGN KEY (research_universe_id) REFERENCES ai_research_universe (id),
    CONSTRAINT fk_strategy_release_model
        FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_prediction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sample_id BIGINT NOT NULL,
    strategy_release_id BIGINT NOT NULL,
    model_version_id BIGINT NULL,
    stock_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    sample_phase VARCHAR(32) NOT NULL,
    inference_mode VARCHAR(32) NOT NULL,
    horizon_trading_days INT NOT NULL,
    expected_return DECIMAL(12, 6) NOT NULL DEFAULT 0,
    expected_excess_return DECIMAL(12, 6) NOT NULL DEFAULT 0,
    probability_up DECIMAL(10, 6) NOT NULL DEFAULT 0,
    probability_down DECIMAL(10, 6) NOT NULL DEFAULT 0,
    calibrated_confidence DECIMAL(10, 4) NOT NULL DEFAULT 0,
    score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    risk_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    rank_no INT NULL,
    action VARCHAR(16) NOT NULL DEFAULT 'WATCH',
    action_bucket VARCHAR(16) NOT NULL DEFAULT 'WATCH',
    target_direction VARCHAR(16) NOT NULL DEFAULT 'SIDEWAYS',
    abstain_reason VARCHAR(255) NULL,
    reason_json MEDIUMTEXT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(192) NOT NULL,
    predicted_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_prediction_idempotency (idempotency_key),
    UNIQUE KEY uk_prediction_business
        (sample_id, horizon_trading_days, strategy_release_id, inference_mode, input_fingerprint),
    KEY idx_prediction_sample (sample_id, horizon_trading_days),
    KEY idx_prediction_trade_rank
        (trade_date, sample_phase, inference_mode, action_bucket, rank_no),
    KEY idx_prediction_strategy_horizon
        (strategy_release_id, horizon_trading_days, sample_id),
    KEY idx_prediction_model (model_version_id, trade_date),
    CONSTRAINT chk_prediction_horizon CHECK (horizon_trading_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_prediction_sample FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_prediction_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_prediction_model
        FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_sample_label (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sample_id BIGINT NOT NULL,
    entry_calendar_id BIGINT NULL,
    exit_calendar_id BIGINT NULL,
    stock_code VARCHAR(16) NOT NULL,
    horizon_trading_days INT NOT NULL,
    label_version VARCHAR(32) NOT NULL,
    revision_no INT NOT NULL DEFAULT 1,
    is_current TINYINT NOT NULL DEFAULT 1,
    supersedes_label_id BIGINT NULL,
    revision_reason VARCHAR(64) NULL,
    calendar_version VARCHAR(64) NOT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    entry_trade_date DATE NULL,
    planned_exit_trade_date DATE NULL,
    exit_trade_date DATE NULL,
    exit_delay_trading_days INT NOT NULL DEFAULT 0,
    entry_price DECIMAL(18, 4) NULL,
    exit_price DECIMAL(18, 4) NULL,
    gross_return DECIMAL(12, 6) NULL,
    net_return DECIMAL(12, 6) NULL,
    benchmark_return DECIMAL(12, 6) NULL,
    sector_return DECIMAL(12, 6) NULL,
    excess_return DECIMAL(12, 6) NULL,
    sector_excess_return DECIMAL(12, 6) NULL,
    sector_membership_fingerprint VARCHAR(128) NULL,
    max_favorable_return DECIMAL(12, 6) NULL,
    max_adverse_return DECIMAL(12, 6) NULL,
    max_drawdown DECIMAL(12, 6) NULL,
    holding_volatility DECIMAL(12, 6) NULL,
    holding_trading_days INT NULL,
    actual_direction VARCHAR(16) NULL,
    fill_status VARCHAR(32) NOT NULL DEFAULT 'INVALID_SOURCE',
    execution_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    execution_reason VARCHAR(255) NULL,
    label_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    policy_snapshot_json MEDIUMTEXT NOT NULL,
    market_evidence_json MEDIUMTEXT NOT NULL,
    label_available_at DATETIME(3) NULL,
    matured_at DATETIME(3) NULL,
    verified_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_sample_label_version
        (sample_id, horizon_trading_days, label_version, revision_no),
    KEY idx_sample_label_current
        (sample_id, horizon_trading_days, label_version, is_current),
    KEY idx_label_maturity_status
        (label_available_at, execution_status, horizon_trading_days),
    KEY idx_sample_label_stock_exit (stock_code, exit_trade_date),
    KEY idx_sample_label_fill_status
        (label_version, fill_status, execution_status, is_current),
    KEY idx_label_sector_evidence
        (label_version, is_current, sector_membership_fingerprint, label_available_at),
    KEY idx_sample_label_calendar (entry_calendar_id, exit_calendar_id),
    CONSTRAINT chk_sample_label_horizon CHECK (horizon_trading_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_sample_label_sample FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_sample_label_entry_calendar
        FOREIGN KEY (entry_calendar_id) REFERENCES ai_trading_calendar (id),
    CONSTRAINT fk_sample_label_exit_calendar
        FOREIGN KEY (exit_calendar_id) REFERENCES ai_trading_calendar (id),
    CONSTRAINT fk_sample_label_supersedes
        FOREIGN KEY (supersedes_label_id) REFERENCES ai_sample_label (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_label_cost_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sample_label_id BIGINT NOT NULL,
    cost_model_version VARCHAR(64) NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    quantity DECIMAL(20, 4) NOT NULL,
    entry_notional DECIMAL(20, 6) NOT NULL,
    exit_notional DECIMAL(20, 6) NOT NULL,
    buy_commission_rate DECIMAL(12, 8) NOT NULL,
    sell_commission_rate DECIMAL(12, 8) NOT NULL,
    stamp_duty_rate DECIMAL(12, 8) NOT NULL,
    transfer_fee_rate DECIMAL(12, 8) NOT NULL,
    slippage_bps DECIMAL(12, 4) NOT NULL,
    impact_cost_bps DECIMAL(12, 4) NOT NULL,
    buy_commission_amount DECIMAL(20, 6) NOT NULL,
    sell_commission_amount DECIMAL(20, 6) NOT NULL,
    stamp_duty_amount DECIMAL(20, 6) NOT NULL,
    transfer_fee_amount DECIMAL(20, 6) NOT NULL,
    slippage_amount DECIMAL(20, 6) NOT NULL,
    impact_cost_amount DECIMAL(20, 6) NOT NULL,
    total_cost_amount DECIMAL(20, 6) NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_label_cost_evidence_version (sample_label_id, cost_model_version),
    KEY idx_label_cost_evidence_source (source_fingerprint),
    CONSTRAINT fk_label_cost_evidence_label
        FOREIGN KEY (sample_label_id) REFERENCES ai_sample_label (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_prediction_evaluation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    prediction_id BIGINT NOT NULL,
    sample_label_id BIGINT NOT NULL,
    evaluation_version VARCHAR(64) NOT NULL,
    direction_correct TINYINT NULL,
    action_effective TINYINT NULL,
    probability_error DECIMAL(12, 6) NULL,
    predicted_return_error DECIMAL(12, 6) NULL,
    net_return DECIMAL(12, 6) NULL,
    excess_return DECIMAL(12, 6) NULL,
    evaluation_score DECIMAL(10, 4) NULL,
    evaluation_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    evidence_json MEDIUMTEXT NOT NULL,
    evaluated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_prediction_evaluation
        (prediction_id, sample_label_id, evaluation_version),
    KEY idx_prediction_evaluation_label (sample_label_id, evaluation_status),
    KEY idx_prediction_evaluation_time (evaluated_at, evaluation_status),
    CONSTRAINT fk_prediction_evaluation_prediction
        FOREIGN KEY (prediction_id) REFERENCES ai_prediction (id),
    CONSTRAINT fk_prediction_evaluation_label
        FOREIGN KEY (sample_label_id) REFERENCES ai_sample_label (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_factor_performance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    factor_definition_id BIGINT NOT NULL,
    horizon_trading_days INT NOT NULL,
    market_regime VARCHAR(32) NOT NULL,
    window_type VARCHAR(32) NOT NULL,
    window_start_date DATE NOT NULL,
    window_end_date DATE NOT NULL,
    revision_no INT NOT NULL DEFAULT 1,
    is_current TINYINT NOT NULL DEFAULT 1,
    supersedes_performance_id BIGINT NULL,
    revision_reason VARCHAR(64) NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    success_rate DECIMAL(10, 4) NOT NULL DEFAULT 0,
    wilson_lower_bound DECIMAL(10, 4) NOT NULL DEFAULT 0,
    rank_ic DECIMAL(12, 6) NULL,
    avg_excess_return DECIMAL(12, 6) NULL,
    avg_adverse_return DECIMAL(12, 6) NULL,
    stability_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    psi_score DECIMAL(10, 6) NULL,
    confidence_level VARCHAR(32) NOT NULL DEFAULT 'LOW_SAMPLE',
    drift_status VARCHAR(32) NOT NULL DEFAULT 'UNASSESSED',
    evaluated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_factor_performance_window
        (factor_definition_id, horizon_trading_days, market_regime, window_type,
         window_start_date, window_end_date, revision_no),
    KEY idx_factor_performance_current
        (factor_definition_id, horizon_trading_days, market_regime, window_type,
         window_start_date, window_end_date, is_current),
    KEY idx_factor_performance_rank
        (horizon_trading_days, confidence_level, wilson_lower_bound),
    KEY idx_factor_performance_window
        (factor_definition_id, window_start_date, window_end_date),
    CONSTRAINT chk_factor_performance_window CHECK (window_start_date <= window_end_date),
    CONSTRAINT chk_factor_performance_horizon CHECK (horizon_trading_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_factor_performance_definition
        FOREIGN KEY (factor_definition_id) REFERENCES ai_factor_definition (id),
    CONSTRAINT fk_factor_performance_supersedes
        FOREIGN KEY (supersedes_performance_id) REFERENCES ai_factor_performance (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_training_dataset_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    training_dataset_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    sample_label_id BIGINT NOT NULL,
    split_type VARCHAR(16) NOT NULL,
    sequence_no INT NOT NULL,
    sample_as_of_time DATETIME(3) NOT NULL,
    label_available_at DATETIME(3) NOT NULL,
    feature_fingerprint VARCHAR(128) NOT NULL,
    label_fingerprint VARCHAR(128) NOT NULL,
    universe_fingerprint VARCHAR(128) NOT NULL,
    trading_state_fingerprint VARCHAR(128) NOT NULL,
    sector_membership_fingerprint VARCHAR(128) NOT NULL,
    included_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_training_dataset_item_lineage
        (training_dataset_id, sample_id, sample_label_id),
    UNIQUE KEY uk_training_dataset_item_sequence
        (training_dataset_id, split_type, sequence_no),
    KEY idx_training_dataset_item_split
        (training_dataset_id, split_type, sample_as_of_time),
    KEY idx_training_dataset_item_label (sample_label_id),
    KEY idx_training_dataset_item_pit_lineage
        (training_dataset_id, universe_fingerprint, trading_state_fingerprint,
         sector_membership_fingerprint),
    CONSTRAINT chk_training_dataset_visibility CHECK (label_available_at <= included_at),
    CONSTRAINT fk_training_dataset_item_dataset
        FOREIGN KEY (training_dataset_id) REFERENCES ai_training_dataset (id),
    CONSTRAINT fk_training_dataset_item_sample
        FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_training_dataset_item_label
        FOREIGN KEY (sample_label_id) REFERENCES ai_sample_label (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_walk_forward_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    training_dataset_id BIGINT NOT NULL,
    strategy_release_id BIGINT NULL,
    model_version_id BIGINT NULL,
    run_key VARCHAR(160) NOT NULL,
    engine_version VARCHAR(64) NOT NULL,
    config_json MEDIUMTEXT NOT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    random_seed BIGINT NOT NULL,
    purge_trading_days INT NOT NULL DEFAULT 5,
    embargo_trading_days INT NOT NULL DEFAULT 5,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    aggregate_metrics_json MEDIUMTEXT NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_walk_forward_run_key (run_key),
    KEY idx_walk_forward_dataset (training_dataset_id, status, created_at),
    KEY idx_walk_forward_release (strategy_release_id, status, created_at),
    CONSTRAINT fk_walk_forward_dataset
        FOREIGN KEY (training_dataset_id) REFERENCES ai_training_dataset (id),
    CONSTRAINT fk_walk_forward_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_walk_forward_model
        FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_walk_forward_fold (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    walk_forward_run_id BIGINT NOT NULL,
    fold_no INT NOT NULL,
    train_start_date DATE NOT NULL,
    train_end_date DATE NOT NULL,
    validation_start_date DATE NOT NULL,
    validation_end_date DATE NOT NULL,
    test_start_date DATE NOT NULL,
    test_end_date DATE NOT NULL,
    train_sample_count INT NOT NULL DEFAULT 0,
    validation_sample_count INT NOT NULL DEFAULT 0,
    test_sample_count INT NOT NULL DEFAULT 0,
    metrics_json MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_walk_forward_fold_no (walk_forward_run_id, fold_no),
    KEY idx_walk_forward_fold_test (walk_forward_run_id, test_start_date, test_end_date, status),
    CONSTRAINT chk_walk_forward_fold_dates CHECK (
        train_start_date <= train_end_date
        AND train_end_date < validation_start_date
        AND validation_start_date <= validation_end_date
        AND validation_end_date < test_start_date
        AND test_start_date <= test_end_date
    ),
    CONSTRAINT fk_walk_forward_fold_run
        FOREIGN KEY (walk_forward_run_id) REFERENCES ai_walk_forward_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_walk_forward_baseline (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    walk_forward_fold_id BIGINT NOT NULL,
    strategy_release_id BIGINT NULL,
    baseline_key VARCHAR(96) NOT NULL,
    baseline_type VARCHAR(32) NOT NULL,
    benchmark_code VARCHAR(32) NULL,
    metrics_json MEDIUMTEXT NOT NULL,
    nav_json MEDIUMTEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_walk_forward_baseline_key (walk_forward_fold_id, baseline_key),
    KEY idx_walk_forward_baseline_type (walk_forward_fold_id, baseline_type),
    CONSTRAINT fk_walk_forward_baseline_fold
        FOREIGN KEY (walk_forward_fold_id) REFERENCES ai_walk_forward_fold (id),
    CONSTRAINT fk_walk_forward_baseline_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_portfolio_backtest_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    training_dataset_id BIGINT NOT NULL,
    walk_forward_run_id BIGINT NULL,
    strategy_release_id BIGINT NOT NULL,
    model_version_id BIGINT NULL,
    run_key VARCHAR(160) NOT NULL,
    engine_version VARCHAR(64) NOT NULL,
    config_fingerprint VARCHAR(128) NOT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    random_seed BIGINT NOT NULL,
    start_trade_date DATE NOT NULL,
    end_trade_date DATE NOT NULL,
    horizon_trading_days INT NOT NULL,
    top_k INT NOT NULL,
    rebalance_frequency VARCHAR(32) NOT NULL,
    initial_capital DECIMAL(20, 6) NOT NULL,
    final_nav DECIMAL(20, 8) NULL,
    benchmark_final_nav DECIMAL(20, 8) NULL,
    total_return DECIMAL(12, 6) NULL,
    benchmark_return DECIMAL(12, 6) NULL,
    alpha DECIMAL(12, 6) NULL,
    annualized_return DECIMAL(12, 6) NULL,
    sharpe_ratio DECIMAL(12, 6) NULL,
    calmar_ratio DECIMAL(12, 6) NULL,
    max_drawdown DECIMAL(12, 6) NULL,
    turnover_rate DECIMAL(12, 6) NULL,
    trade_count INT NOT NULL DEFAULT 0,
    metrics_json MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_portfolio_backtest_run_key (run_key),
    KEY idx_portfolio_backtest_release
        (strategy_release_id, start_trade_date, end_trade_date, status),
    KEY idx_portfolio_backtest_dataset (training_dataset_id, created_at),
    KEY idx_portfolio_backtest_status (status, created_at),
    CONSTRAINT chk_portfolio_backtest_dates CHECK (start_trade_date <= end_trade_date),
    CONSTRAINT chk_portfolio_backtest_horizon CHECK (horizon_trading_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_portfolio_backtest_dataset
        FOREIGN KEY (training_dataset_id) REFERENCES ai_training_dataset (id),
    CONSTRAINT fk_portfolio_backtest_walk_run
        FOREIGN KEY (walk_forward_run_id) REFERENCES ai_walk_forward_run (id),
    CONSTRAINT fk_portfolio_backtest_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_portfolio_backtest_model
        FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_portfolio_backtest_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    backtest_run_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    cash_balance DECIMAL(20, 6) NOT NULL,
    market_value DECIMAL(20, 6) NOT NULL,
    total_equity DECIMAL(20, 6) NOT NULL,
    nav DECIMAL(20, 8) NOT NULL,
    benchmark_nav DECIMAL(20, 8) NOT NULL,
    daily_return DECIMAL(12, 6) NOT NULL,
    benchmark_return DECIMAL(12, 6) NOT NULL,
    drawdown DECIMAL(12, 6) NOT NULL,
    turnover_rate DECIMAL(12, 6) NOT NULL,
    gross_exposure DECIMAL(12, 6) NOT NULL,
    net_exposure DECIMAL(12, 6) NOT NULL,
    holding_count INT NOT NULL,
    transaction_cost DECIMAL(20, 6) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_portfolio_backtest_daily_date (backtest_run_id, trade_date),
    KEY idx_portfolio_backtest_daily_nav (backtest_run_id, trade_date, nav),
    CONSTRAINT fk_portfolio_backtest_daily_run
        FOREIGN KEY (backtest_run_id) REFERENCES ai_portfolio_backtest_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_portfolio_backtest_trade (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    backtest_run_id BIGINT NOT NULL,
    prediction_id BIGINT NULL,
    trade_key VARCHAR(128) NOT NULL,
    trade_date DATE NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    side VARCHAR(16) NOT NULL,
    order_quantity DECIMAL(20, 4) NOT NULL,
    filled_quantity DECIMAL(20, 4) NOT NULL,
    execution_price DECIMAL(18, 4) NULL,
    gross_amount DECIMAL(20, 6) NULL,
    commission_amount DECIMAL(20, 6) NOT NULL DEFAULT 0,
    stamp_duty_amount DECIMAL(20, 6) NOT NULL DEFAULT 0,
    transfer_fee_amount DECIMAL(20, 6) NOT NULL DEFAULT 0,
    slippage_amount DECIMAL(20, 6) NOT NULL DEFAULT 0,
    total_cost_amount DECIMAL(20, 6) NOT NULL DEFAULT 0,
    execution_status VARCHAR(32) NOT NULL,
    rejection_reason VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_portfolio_backtest_trade_key (backtest_run_id, trade_key),
    KEY idx_portfolio_backtest_trade_stock (backtest_run_id, stock_code, trade_date),
    KEY idx_portfolio_backtest_trade_prediction (prediction_id),
    CONSTRAINT fk_portfolio_backtest_trade_run
        FOREIGN KEY (backtest_run_id) REFERENCES ai_portfolio_backtest_run (id),
    CONSTRAINT fk_portfolio_backtest_trade_prediction
        FOREIGN KEY (prediction_id) REFERENCES ai_prediction (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_portfolio_backtest_position (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    backtest_run_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    quantity DECIMAL(20, 4) NOT NULL,
    average_cost DECIMAL(18, 4) NOT NULL,
    close_price DECIMAL(18, 4) NOT NULL,
    market_value DECIMAL(20, 6) NOT NULL,
    weight DECIMAL(12, 6) NOT NULL,
    unrealized_pnl DECIMAL(20, 6) NOT NULL,
    daily_pnl DECIMAL(20, 6) NOT NULL,
    return_contribution DECIMAL(12, 6) NOT NULL,
    tradable_status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_portfolio_backtest_position_stock
        (backtest_run_id, trade_date, stock_code),
    KEY idx_portfolio_backtest_position_weight (backtest_run_id, trade_date, weight),
    KEY idx_portfolio_backtest_position_stock (stock_code, trade_date),
    CONSTRAINT fk_portfolio_backtest_position_run
        FOREIGN KEY (backtest_run_id) REFERENCES ai_portfolio_backtest_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_pipeline_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scope_type VARCHAR(16) NOT NULL,
    owner_user_id BIGINT NULL,
    parent_run_id BIGINT NULL,
    data_batch_id BIGINT NULL,
    strategy_release_id BIGINT NULL,
    model_version_id BIGINT NULL,
    trade_date DATE NOT NULL,
    pipeline_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    execution_owner VARCHAR(64) NULL,
    lease_until DATETIME(3) NULL,
    next_retry_at DATETIME(3) NULL,
    current_step VARCHAR(64) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    processed_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    error_detail MEDIUMTEXT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_pipeline_run_idempotency (idempotency_key),
    KEY idx_pipeline_scope_trade_status (scope_type, trade_date, status),
    KEY idx_pipeline_owner_trade (owner_user_id, trade_date, pipeline_type, status),
    KEY idx_pipeline_run_retry_lease (status, next_retry_at, lease_until),
    KEY idx_pipeline_parent (parent_run_id, scope_type, status),
    KEY idx_pipeline_batch (data_batch_id),
    KEY idx_pipeline_release (strategy_release_id, trade_date),
    CONSTRAINT chk_pipeline_scope CHECK (
        (scope_type = 'GLOBAL' AND owner_user_id IS NULL)
        OR (scope_type = 'USER' AND owner_user_id IS NOT NULL)
    ),
    CONSTRAINT fk_pipeline_owner FOREIGN KEY (owner_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_pipeline_parent FOREIGN KEY (parent_run_id) REFERENCES ai_pipeline_run (id),
    CONSTRAINT fk_pipeline_batch FOREIGN KEY (data_batch_id) REFERENCES ai_data_batch (id),
    CONSTRAINT fk_pipeline_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_pipeline_model FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_pipeline_step (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pipeline_run_id BIGINT NOT NULL,
    step_key VARCHAR(64) NOT NULL,
    step_order INT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NULL,
    lease_until DATETIME(3) NULL,
    input_count INT NOT NULL DEFAULT 0,
    output_count INT NOT NULL DEFAULT 0,
    checkpoint_json MEDIUMTEXT NULL,
    output_fingerprint VARCHAR(128) NULL,
    error_message TEXT NULL,
    error_detail MEDIUMTEXT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_pipeline_step_run_key (pipeline_run_id, step_key),
    KEY idx_pipeline_step_status (pipeline_run_id, status, step_order),
    KEY idx_pipeline_retry_lease (status, next_retry_at, lease_until),
    CONSTRAINT fk_pipeline_step_run
        FOREIGN KEY (pipeline_run_id) REFERENCES ai_pipeline_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_shadow_evaluation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pipeline_run_id BIGINT NULL,
    training_dataset_id BIGINT NULL,
    champion_release_id BIGINT NOT NULL,
    challenger_release_id BIGINT NOT NULL,
    champion_model_version_id BIGINT NULL,
    challenger_model_version_id BIGINT NULL,
    window_start_date DATE NOT NULL,
    window_end_date DATE NOT NULL,
    evaluation_version VARCHAR(64) NOT NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    eligible_sample_count INT NOT NULL DEFAULT 0,
    coverage_rate DECIMAL(10, 6) NOT NULL DEFAULT 0,
    action_agreement_rate DECIMAL(10, 6) NULL,
    champion_calibration_error DECIMAL(12, 6) NULL,
    challenger_calibration_error DECIMAL(12, 6) NULL,
    champion_excess_return DECIMAL(12, 6) NULL,
    challenger_excess_return DECIMAL(12, 6) NULL,
    champion_max_drawdown DECIMAL(12, 6) NULL,
    challenger_max_drawdown DECIMAL(12, 6) NULL,
    feature_drift_score DECIMAL(12, 6) NULL,
    metrics_json MEDIUMTEXT NOT NULL,
    decision_status VARCHAR(32) NOT NULL DEFAULT 'OBSERVING',
    evaluated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_shadow_evaluation_window
        (champion_release_id, challenger_release_id, window_start_date, window_end_date, evaluation_version),
    KEY idx_shadow_evaluation_candidate (decision_status, evaluated_at),
    KEY idx_shadow_evaluation_challenger (challenger_release_id, window_end_date),
    CONSTRAINT chk_shadow_evaluation_window CHECK (window_start_date <= window_end_date),
    CONSTRAINT chk_shadow_evaluation_releases CHECK (champion_release_id <> challenger_release_id),
    CONSTRAINT fk_shadow_evaluation_pipeline
        FOREIGN KEY (pipeline_run_id) REFERENCES ai_pipeline_run (id),
    CONSTRAINT fk_shadow_evaluation_dataset
        FOREIGN KEY (training_dataset_id) REFERENCES ai_training_dataset (id),
    CONSTRAINT fk_shadow_evaluation_champion
        FOREIGN KEY (champion_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_shadow_evaluation_challenger
        FOREIGN KEY (challenger_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_shadow_evaluation_champion_model
        FOREIGN KEY (champion_model_version_id) REFERENCES ai_model_version (id),
    CONSTRAINT fk_shadow_evaluation_challenger_model
        FOREIGN KEY (challenger_model_version_id) REFERENCES ai_model_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_shadow_evaluation_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shadow_evaluation_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    champion_prediction_id BIGINT NOT NULL,
    challenger_prediction_id BIGINT NOT NULL,
    sample_label_id BIGINT NULL,
    horizon_trading_days INT NOT NULL,
    action_agreement TINYINT NOT NULL,
    score_delta DECIMAL(12, 6) NULL,
    confidence_delta DECIMAL(12, 6) NULL,
    challenger_excess_return DECIMAL(12, 6) NULL,
    evaluation_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_LABEL',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_shadow_evaluation_item_sample
        (shadow_evaluation_id, sample_id, horizon_trading_days),
    KEY idx_shadow_item_challenger (challenger_prediction_id, evaluation_status),
    KEY idx_shadow_item_label (sample_label_id),
    CONSTRAINT chk_shadow_item_predictions CHECK (champion_prediction_id <> challenger_prediction_id),
    CONSTRAINT chk_shadow_item_horizon CHECK (horizon_trading_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_shadow_item_evaluation
        FOREIGN KEY (shadow_evaluation_id) REFERENCES ai_shadow_evaluation (id),
    CONSTRAINT fk_shadow_item_sample FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_shadow_item_champion
        FOREIGN KEY (champion_prediction_id) REFERENCES ai_prediction (id),
    CONSTRAINT fk_shadow_item_challenger
        FOREIGN KEY (challenger_prediction_id) REFERENCES ai_prediction (id),
    CONSTRAINT fk_shadow_item_label
        FOREIGN KEY (sample_label_id) REFERENCES ai_sample_label (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_drift_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    factor_performance_id BIGINT NULL,
    model_version_id BIGINT NULL,
    strategy_release_id BIGINT NULL,
    shadow_evaluation_id BIGINT NULL,
    event_fingerprint VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_key VARCHAR(160) NOT NULL,
    detector_version VARCHAR(64) NOT NULL,
    window_start_date DATE NOT NULL,
    window_end_date DATE NOT NULL,
    metric_name VARCHAR(64) NOT NULL,
    baseline_value DECIMAL(20, 8) NULL,
    observed_value DECIMAL(20, 8) NULL,
    threshold_value DECIMAL(20, 8) NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    evidence_json MEDIUMTEXT NOT NULL,
    detected_at DATETIME(3) NOT NULL,
    acknowledged_at DATETIME(3) NULL,
    resolved_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_drift_event_fingerprint (event_fingerprint),
    KEY idx_drift_event_open (status, severity, detected_at),
    KEY idx_drift_event_subject (subject_type, subject_key, window_end_date),
    KEY idx_drift_event_performance (factor_performance_id),
    CONSTRAINT chk_drift_event_window CHECK (window_start_date <= window_end_date),
    CONSTRAINT fk_drift_event_performance
        FOREIGN KEY (factor_performance_id) REFERENCES ai_factor_performance (id),
    CONSTRAINT fk_drift_event_model
        FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id),
    CONSTRAINT fk_drift_event_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_drift_event_shadow
        FOREIGN KEY (shadow_evaluation_id) REFERENCES ai_shadow_evaluation (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_strategy_governance_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_release_id BIGINT NOT NULL,
    previous_champion_release_id BIGINT NULL,
    walk_forward_run_id BIGINT NULL,
    backtest_run_id BIGINT NULL,
    shadow_evaluation_id BIGINT NULL,
    event_key VARCHAR(160) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    decision_status VARCHAR(32) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_user_id BIGINT NULL,
    reason TEXT NOT NULL,
    threshold_snapshot_json MEDIUMTEXT NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_strategy_governance_event_key (event_key),
    KEY idx_strategy_governance_release (strategy_release_id, occurred_at),
    KEY idx_strategy_governance_decision (decision_status, occurred_at),
    CONSTRAINT fk_strategy_governance_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_strategy_governance_previous
        FOREIGN KEY (previous_champion_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_strategy_governance_walk_run
        FOREIGN KEY (walk_forward_run_id) REFERENCES ai_walk_forward_run (id),
    CONSTRAINT fk_strategy_governance_backtest
        FOREIGN KEY (backtest_run_id) REFERENCES ai_portfolio_backtest_run (id),
    CONSTRAINT fk_strategy_governance_shadow
        FOREIGN KEY (shadow_evaluation_id) REFERENCES ai_shadow_evaluation (id),
    CONSTRAINT fk_strategy_governance_actor
        FOREIGN KEY (actor_user_id) REFERENCES user_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_analysis_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    strategy_release_id BIGINT NOT NULL,
    prompt_template_id BIGINT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    report_date DATE NOT NULL,
    report_version INT NOT NULL DEFAULT 1,
    supersedes_report_id BIGINT NULL,
    idempotency_key VARCHAR(192) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    system_score DECIMAL(10, 4) NULL,
    final_action VARCHAR(16) NULL,
    target_direction VARCHAR(16) NULL,
    risk_score DECIMAL(10, 4) NULL,
    risk_level VARCHAR(16) NULL,
    calibrated_confidence DECIMAL(10, 4) NULL,
    data_quality_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    advice VARCHAR(64) NULL,
    technical_analysis TEXT NULL,
    risk_warning TEXT NULL,
    buy_sell_points TEXT NULL,
    conditional_strategy MEDIUMTEXT NULL,
    prompt_summary TEXT NULL,
    raw_prompt MEDIUMTEXT NULL,
    raw_response MEDIUMTEXT NULL,
    source_model VARCHAR(128) NULL,
    error_message TEXT NULL,
    generated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_analysis_report_version
        (user_id, stock_code, report_date, report_version),
    UNIQUE KEY uk_analysis_report_idempotency (user_id, idempotency_key),
    UNIQUE KEY uk_analysis_report_user_id (user_id, id),
    KEY idx_analysis_report_user_time (user_id, generated_at),
    KEY idx_analysis_report_user_date (user_id, report_date, generated_at),
    KEY idx_analysis_report_sample (sample_id),
    KEY idx_analysis_report_release (strategy_release_id, report_date),
    CONSTRAINT fk_analysis_report_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_analysis_report_sample FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_analysis_report_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_analysis_report_prompt
        FOREIGN KEY (prompt_template_id) REFERENCES ai_prompt_template (id),
    CONSTRAINT fk_analysis_report_supersedes
        FOREIGN KEY (user_id, supersedes_report_id)
        REFERENCES ai_analysis_report (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_analysis_report_prediction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    prediction_id BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    weight DECIMAL(10, 6) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_report_prediction (report_id, prediction_id, purpose),
    KEY idx_report_prediction_user (user_id, report_id, purpose),
    KEY idx_report_prediction_prediction (prediction_id),
    CONSTRAINT fk_report_prediction_report
        FOREIGN KEY (user_id, report_id) REFERENCES ai_analysis_report (user_id, id),
    CONSTRAINT fk_report_prediction_prediction
        FOREIGN KEY (prediction_id) REFERENCES ai_prediction (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_trade_rule_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    strategy_release_id BIGINT NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    config_json MEDIUMTEXT NOT NULL,
    seed_version VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trade_rule_config_user_version (user_id, version_no),
    UNIQUE KEY uk_trade_rule_config_user_id (user_id, id),
    KEY idx_trade_rule_config_active (user_id, status, updated_at),
    KEY idx_trade_rule_config_release (strategy_release_id, status),
    CONSTRAINT fk_trade_rule_config_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_trade_rule_config_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_trade_plan_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    prediction_id BIGINT NULL,
    sample_label_id BIGINT NULL,
    prediction_evaluation_id BIGINT NULL,
    trade_rule_config_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    report_date DATE NOT NULL,
    horizon_trading_days INT NOT NULL,
    target_trade_date DATE NULL,
    outcome_trade_date DATE NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    triggered_rule_code VARCHAR(64) NULL,
    rule_type VARCHAR(32) NOT NULL DEFAULT 'HORIZON_PLAN',
    triggered_state VARCHAR(64) NULL,
    suggested_action VARCHAR(32) NULL,
    market_regime VARCHAR(32) NOT NULL DEFAULT 'UNCLASSIFIED',
    trigger_price DECIMAL(18, 4) NULL,
    outcome_price DECIMAL(18, 4) NULL,
    post_trigger_return DECIMAL(12, 6) NULL,
    max_favorable_return DECIMAL(12, 6) NULL,
    max_adverse_return DECIMAL(12, 6) NULL,
    action_effective TINYINT NULL,
    review_score DECIMAL(10, 4) NULL,
    actual_metrics_json MEDIUMTEXT NULL,
    feedback_json MEDIUMTEXT NULL,
    feedback_summary VARCHAR(1024) NULL,
    evaluated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trade_plan_review_report_horizon
        (user_id, report_id, horizon_trading_days),
    KEY idx_trade_plan_review_pending (user_id, status, outcome_trade_date),
    KEY idx_trade_plan_review_rule (user_id, triggered_rule_code, horizon_trading_days),
    KEY idx_trade_plan_review_prediction (prediction_id),
    KEY idx_trade_plan_review_label (sample_label_id),
    KEY idx_trade_plan_review_evaluation (prediction_evaluation_id),
    CONSTRAINT chk_trade_plan_review_horizon
        CHECK (horizon_trading_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_trade_plan_review_report
        FOREIGN KEY (user_id, report_id) REFERENCES ai_analysis_report (user_id, id),
    CONSTRAINT fk_trade_plan_review_prediction
        FOREIGN KEY (prediction_id) REFERENCES ai_prediction (id),
    CONSTRAINT fk_trade_plan_review_label
        FOREIGN KEY (sample_label_id) REFERENCES ai_sample_label (id),
    CONSTRAINT fk_trade_plan_review_evaluation
        FOREIGN KEY (prediction_evaluation_id) REFERENCES ai_prediction_evaluation (id),
    CONSTRAINT fk_trade_plan_review_config
        FOREIGN KEY (user_id, trade_rule_config_id)
        REFERENCES ai_trade_rule_config (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_trade_rule_performance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trade_rule_config_id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    horizon_trading_days INT NOT NULL,
    market_regime VARCHAR(32) NOT NULL DEFAULT 'UNCLASSIFIED',
    window_start_date DATE NOT NULL,
    window_end_date DATE NOT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    effective_count INT NOT NULL DEFAULT 0,
    effectiveness_rate DECIMAL(10, 4) NOT NULL DEFAULT 0,
    avg_post_trigger_return DECIMAL(12, 6) NOT NULL DEFAULT 0,
    avg_adverse_return DECIMAL(12, 6) NOT NULL DEFAULT 0,
    learned_weight DECIMAL(10, 4) NOT NULL DEFAULT 50,
    confidence_level VARCHAR(24) NOT NULL DEFAULT 'LOW_SAMPLE',
    input_fingerprint VARCHAR(128) NOT NULL,
    last_evaluated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trade_rule_performance
        (user_id, trade_rule_config_id, rule_code, horizon_trading_days,
         market_regime, window_start_date, window_end_date),
    KEY idx_trade_rule_performance_weight (user_id, learned_weight, sample_count),
    CONSTRAINT chk_trade_rule_performance_window CHECK (window_start_date <= window_end_date),
    CONSTRAINT chk_trade_rule_performance_horizon
        CHECK (horizon_trading_days IN (1, 2, 3, 5)),
    CONSTRAINT fk_trade_rule_performance_config
        FOREIGN KEY (user_id, trade_rule_config_id)
        REFERENCES ai_trade_rule_config (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_user_strategy_binding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    strategy_release_id BIGINT NOT NULL,
    model_family VARCHAR(64) NOT NULL,
    is_current TINYINT NOT NULL DEFAULT 1,
    current_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN is_current = 1 THEN 1 ELSE NULL END
    ) STORED,
    personalized_config_json MEDIUMTEXT NULL,
    seed_version VARCHAR(64) NULL,
    bound_at DATETIME(3) NOT NULL,
    unbound_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_strategy_binding (user_id, model_family, current_guard),
    KEY idx_user_strategy_binding_release (strategy_release_id, is_current),
    CONSTRAINT chk_user_strategy_binding_current CHECK (is_current IN (0, 1)),
    CONSTRAINT fk_user_strategy_binding_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_user_strategy_binding_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_daily_decision_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    snapshot_version INT NOT NULL DEFAULT 1,
    pipeline_run_id BIGINT NULL,
    global_pipeline_run_id BIGINT NULL,
    strategy_release_id BIGINT NOT NULL,
    model_version_id BIGINT NULL,
    supersedes_snapshot_id BIGINT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    is_current TINYINT NOT NULL DEFAULT 1,
    current_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN is_current = 1 THEN 1 ELSE NULL END
    ) STORED,
    snapshot_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    market_regime VARCHAR(32) NOT NULL DEFAULT 'UNCLASSIFIED',
    recommendation_count INT NOT NULL DEFAULT 0,
    cautious_count INT NOT NULL DEFAULT 0,
    avoid_count INT NOT NULL DEFAULT 0,
    holding_risk_count INT NOT NULL DEFAULT 0,
    unavailable_count INT NOT NULL DEFAULT 0,
    overall_hit_rate DECIMAL(10, 4) NULL,
    freshness_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    data_quality_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    decision_policy_version VARCHAR(64) NOT NULL,
    summary_json MEDIUMTEXT NOT NULL,
    generated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_daily_decision_version (user_id, trade_date, snapshot_version),
    UNIQUE KEY uk_daily_decision_idempotency (user_id, idempotency_key),
    UNIQUE KEY uk_current_daily_decision (user_id, trade_date, current_guard),
    UNIQUE KEY uk_daily_decision_user_id (user_id, id),
    KEY idx_daily_decision_current (user_id, is_current, trade_date),
    KEY idx_daily_decision_status (user_id, snapshot_status, freshness_status),
    CONSTRAINT chk_daily_decision_current CHECK (is_current IN (0, 1)),
    CONSTRAINT fk_daily_decision_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_daily_decision_pipeline
        FOREIGN KEY (pipeline_run_id) REFERENCES ai_pipeline_run (id),
    CONSTRAINT fk_daily_decision_global_pipeline
        FOREIGN KEY (global_pipeline_run_id) REFERENCES ai_pipeline_run (id),
    CONSTRAINT fk_daily_decision_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_daily_decision_model
        FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id),
    CONSTRAINT fk_daily_decision_supersedes
        FOREIGN KEY (user_id, supersedes_snapshot_id)
        REFERENCES ai_daily_decision_snapshot (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_daily_decision_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    decision_snapshot_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    sample_id BIGINT NULL,
    report_id BIGINT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    category VARCHAR(32) NOT NULL,
    system_score DECIMAL(10, 4) NULL,
    horizon_signal_score DECIMAL(10, 4) NULL,
    factor_reliability_score DECIMAL(10, 4) NULL,
    strategy_validation_score DECIMAL(10, 4) NULL,
    data_quality_component DECIMAL(10, 4) NULL,
    risk_component DECIMAL(10, 4) NULL,
    final_action VARCHAR(16) NULL,
    risk_score DECIMAL(10, 4) NULL,
    risk_level VARCHAR(16) NULL,
    decision_source VARCHAR(32) NULL,
    freshness_status VARCHAR(32) NOT NULL,
    decision_policy_version VARCHAR(64) NOT NULL,
    confidence_level VARCHAR(32) NOT NULL DEFAULT 'LOW_SAMPLE',
    out_of_sample_count INT NOT NULL DEFAULT 0,
    historical_hit_rate DECIMAL(10, 4) NULL,
    trigger_factors_json MEDIUMTEXT NULL,
    reason_summary VARCHAR(1024) NULL,
    unavailable_reason VARCHAR(512) NULL,
    input_fingerprint VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_daily_decision_item_stock
        (user_id, decision_snapshot_id, stock_code),
    UNIQUE KEY uk_daily_decision_item_user_id (user_id, id),
    KEY idx_decision_user_trade_action (user_id, trade_date, final_action, category),
    KEY idx_daily_decision_item_snapshot (decision_snapshot_id, category, system_score),
    KEY idx_daily_decision_item_sample (sample_id),
    KEY idx_daily_decision_item_report (report_id),
    CONSTRAINT chk_daily_decision_item_payload CHECK (
        category = 'DATA_UNAVAILABLE'
        OR (
            system_score IS NOT NULL
            AND final_action IS NOT NULL
            AND risk_score IS NOT NULL
            AND risk_level IS NOT NULL
            AND decision_source IS NOT NULL
        )
    ),
    CONSTRAINT fk_daily_decision_item_snapshot
        FOREIGN KEY (user_id, decision_snapshot_id)
        REFERENCES ai_daily_decision_snapshot (user_id, id),
    CONSTRAINT fk_daily_decision_item_sample
        FOREIGN KEY (sample_id) REFERENCES ai_sample (id),
    CONSTRAINT fk_daily_decision_item_report
        FOREIGN KEY (user_id, report_id) REFERENCES ai_analysis_report (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_daily_decision_item_prediction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    decision_item_id BIGINT NOT NULL,
    prediction_id BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    weight DECIMAL(10, 6) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_decision_item_prediction (decision_item_id, prediction_id),
    KEY idx_decision_item_prediction_user (user_id, decision_item_id),
    KEY idx_decision_item_prediction_prediction (prediction_id),
    CONSTRAINT fk_decision_item_prediction_item
        FOREIGN KEY (user_id, decision_item_id)
        REFERENCES ai_daily_decision_item (user_id, id),
    CONSTRAINT fk_decision_item_prediction_prediction
        FOREIGN KEY (prediction_id) REFERENCES ai_prediction (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_research_daily_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    decision_snapshot_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    report_version INT NOT NULL DEFAULT 1,
    pipeline_run_id BIGINT NULL,
    strategy_release_id BIGINT NOT NULL,
    model_version_id BIGINT NULL,
    supersedes_report_id BIGINT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    is_current TINYINT NOT NULL DEFAULT 1,
    current_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN is_current = 1 THEN 1 ELSE NULL END
    ) STORED,
    report_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    title VARCHAR(160) NOT NULL,
    executive_summary TEXT NOT NULL,
    freshness_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    data_quality_score DECIMAL(10, 4) NOT NULL DEFAULT 0,
    content_json MEDIUMTEXT NOT NULL,
    markdown_content MEDIUMTEXT NULL,
    generated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_research_report_version (user_id, trade_date, report_version),
    UNIQUE KEY uk_research_report_idempotency (user_id, idempotency_key),
    UNIQUE KEY uk_current_research_report (user_id, trade_date, current_guard),
    UNIQUE KEY uk_research_report_user_id (user_id, id),
    KEY idx_research_report_current (user_id, is_current, trade_date),
    KEY idx_research_report_status (user_id, report_status, freshness_status),
    CONSTRAINT chk_research_report_current CHECK (is_current IN (0, 1)),
    CONSTRAINT fk_research_report_snapshot
        FOREIGN KEY (user_id, decision_snapshot_id)
        REFERENCES ai_daily_decision_snapshot (user_id, id),
    CONSTRAINT fk_research_report_pipeline
        FOREIGN KEY (pipeline_run_id) REFERENCES ai_pipeline_run (id),
    CONSTRAINT fk_research_report_release
        FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id),
    CONSTRAINT fk_research_report_model
        FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id),
    CONSTRAINT fk_research_report_supersedes
        FOREIGN KEY (user_id, supersedes_report_id)
        REFERENCES ai_research_daily_report (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 可启动的全局研究基线。所有种子均记录版本，后续升级必须创建新版本，不能原地覆盖历史版本。
INSERT INTO ai_research_universe
    (id, universe_code, universe_name, market_code, selection_policy_json,
     minimum_stock_count, enabled, seed_version)
VALUES
    (1, 'CN_A_SYSTEM_CORE', 'A股系统研究池', 'CN_A',
     '{"market":"CN_A","selection":"ACTIVE_LISTED","exclude":["DELISTED","SUSPENDED_LONG_TERM"],"pointInTime":true}',
     200, 1, '20260714-unified-1.1');

INSERT INTO ai_factor_definition
    (id, factor_code, factor_version, factor_name, factor_group, direction,
     formula_desc, required_fields_json, default_weight, enabled, seed_version)
VALUES
    (1, 'MOMENTUM_RETURN_3D', '1.0.0', '3日动量', 'MOMENTUM', 'POSITIVE',
     '(close_t / close_t-3) - 1', '["close","trade_date"]', 6.0000, 1,
     '20260714-unified-1.1'),
    (2, 'MOMENTUM_RETURN_5D', '1.0.0', '5日动量', 'MOMENTUM', 'POSITIVE',
     '(close_t / close_t-5) - 1', '["close","trade_date"]', 8.0000, 1,
     '20260714-unified-1.1'),
    (3, 'TREND_MA5_DISTANCE', '1.0.0', '5日均线距离', 'TREND', 'POSITIVE',
     '(close_t / ma5_t) - 1', '["close","trade_date"]', 6.0000, 1,
     '20260714-unified-1.1'),
    (4, 'TREND_MA20_DISTANCE', '1.0.0', '20日均线距离', 'TREND', 'POSITIVE',
     '(close_t / ma20_t) - 1', '["close","trade_date"]', 8.0000, 1,
     '20260714-unified-1.1'),
    (5, 'VOLUME_RATIO_5D', '1.0.0', '5日量比', 'VOLUME_PRICE', 'POSITIVE',
     'volume_t / avg(volume_t-1..t-5)', '["volume","trade_date"]', 7.0000, 1,
     '20260714-unified-1.1'),
    (6, 'VOLATILITY_10D', '1.0.0', '10日波动率', 'VOLATILITY', 'NEGATIVE',
     'stddev(daily_return, 10)', '["close","trade_date"]', 8.0000, 1,
     '20260714-unified-1.1'),
    (7, 'LIQUIDITY_AVG_AMOUNT_5D', '1.0.0', '5日平均成交额', 'TRADING_CONSTRAINT', 'POSITIVE',
     'avg(amount, 5)', '["amount","trade_date"]', 5.0000, 1,
     '20260714-unified-1.1'),
    (8, 'FUNDAMENTAL_PE', '1.0.0', '市盈率', 'FUNDAMENTAL', 'NEGATIVE',
     'pe_t', '["pe","fetched_at"]', 5.0000, 1, '20260714-unified-1.1'),
    (9, 'FUNDAMENTAL_PB', '1.0.0', '市净率', 'FUNDAMENTAL', 'NEGATIVE',
     'pb_t', '["pb","fetched_at"]', 4.0000, 1, '20260714-unified-1.1'),
    (10, 'FUNDAMENTAL_ROE', '1.0.0', '净资产收益率', 'FUNDAMENTAL', 'POSITIVE',
     'roe_t', '["roe","report_date","published_at"]', 8.0000, 1, '20260714-unified-1.1'),
    (11, 'FUNDAMENTAL_REVENUE_GROWTH', '1.0.0', '营收增长率', 'FUNDAMENTAL', 'POSITIVE',
     'revenue_growth_t', '["revenue_growth","report_date","published_at"]', 6.0000, 1,
     '20260714-unified-1.1'),
    (12, 'FUNDAMENTAL_PROFIT_GROWTH', '1.0.0', '利润增长率', 'FUNDAMENTAL', 'POSITIVE',
     'profit_growth_t', '["profit_growth","report_date","published_at"]', 8.0000, 1,
     '20260714-unified-1.1'),
    (13, 'FUNDAMENTAL_DEBT_RATIO', '1.0.0', '资产负债率', 'FUNDAMENTAL', 'NEGATIVE',
     'debt_ratio_t', '["debt_ratio","report_date","published_at"]', 5.0000, 1,
     '20260714-unified-1.1'),
    (14, 'MARKET_RELATIVE_STRENGTH', '1.0.0', '市场相对强度', 'MARKET', 'POSITIVE',
     'stock_return_3d - benchmark_return_3d', '["stock_close","benchmark_close","trade_date"]',
     8.0000, 1, '20260714-unified-1.1'),
    (15, 'SECTOR_RELATIVE_STRENGTH', '1.0.0', '板块相对强度', 'SECTOR', 'POSITIVE',
     'stock_return_3d - sector_return_3d', '["stock_close","sector_close","trade_date"]',
     8.0000, 1, '20260714-unified-1.1'),
    (16, 'NEWS_SENTIMENT', '1.0.0', '资讯情绪', 'NEWS', 'POSITIVE',
     'point_in_time_sentiment', '["published_at","available_at","sentiment_score"]', 6.0000, 1,
     '20260714-unified-1.1');

INSERT INTO ai_strategy_release
    (id, research_universe_id, model_family, version_no, title, model_version_id,
     status, release_role, config_json, factor_snapshot_json, validation_metrics_json,
     promotion_reason, seed_version, activated_at)
VALUES
    (1, 1, 'A_SHARE_MULTI_HORIZON', 'RULE_BASELINE/1.0.0', 'A股多周期规则基线', NULL,
     'ACTIVE', 'CHAMPION',
     '{"predictionHorizons":[1,2,3,5],"minimumDataQuality":70,"minimumSampleCount":30,"decisionPolicy":"CONDITIONAL_ONLY","allowUnknownDecision":false}',
     '{"factorVersion":"1.0.0","factorCodes":["MOMENTUM_RETURN_3D","MOMENTUM_RETURN_5D","TREND_MA5_DISTANCE","TREND_MA20_DISTANCE","VOLUME_RATIO_5D","VOLATILITY_10D","LIQUIDITY_AVG_AMOUNT_5D","FUNDAMENTAL_PE","FUNDAMENTAL_PB","FUNDAMENTAL_ROE","FUNDAMENTAL_REVENUE_GROWTH","FUNDAMENTAL_PROFIT_GROWTH","FUNDAMENTAL_DEBT_RATIO","MARKET_RELATIVE_STRENGTH","SECTOR_RELATIVE_STRENGTH","NEWS_SENTIMENT"]}',
     '{"status":"BASELINE_NOT_VALIDATED","sampleCount":0,"promotionEligible":false}',
     '统一研究域初始化基线，仅作为首个可运行 Champion；后续版本必须通过走步验证、回测和影子评估后晋级。',
     '20260714-unified-1.1', CURRENT_TIMESTAMP(3));

INSERT INTO ai_strategy_governance_event
    (strategy_release_id, previous_champion_release_id, event_key, event_type,
     decision_status, policy_version, actor_type, reason, threshold_snapshot_json,
     evidence_json, occurred_at)
VALUES
    (1, NULL, 'seed:20260714-unified-1.1:baseline-champion', 'INITIALIZE',
     'APPROVED_BASELINE', 'GOVERNANCE/1.0.0', 'SYSTEM',
     '统一研究域初始化需要一个可运行基线；该事件不代表策略已经通过真实样本验证。',
     '{"minimumSamplesForPromotion":30,"requireWalkForward":true,"requireShadowEvaluation":true}',
     '{"seedVersion":"20260714-unified-1.1","validationStatus":"NOT_VALIDATED"}',
     CURRENT_TIMESTAMP(3));

INSERT INTO ai_user_strategy_binding
    (user_id, strategy_release_id, model_family, is_current,
     personalized_config_json, seed_version, bound_at)
SELECT
    id, 1, 'A_SHARE_MULTI_HORIZON', 1,
    JSON_OBJECT('riskPreference', COALESCE(risk_preference, 'BALANCED')),
    '20260714-unified-1.1', CURRENT_TIMESTAMP(3)
FROM user_account
WHERE deleted = 0;

INSERT INTO ai_trade_rule_config
    (user_id, strategy_release_id, version_no, name, status, config_json, seed_version)
SELECT
    id, 1, 'CONDITIONAL_RULE/1.0.0', '条件交易规则基线', 'ACTIVE',
    '{"mode":"IF_THEN_ONLY","horizons":[1,2,3],"buyModels":["BREAKOUT","PULLBACK","TREND_CONFIRMATION"],"sellModels":["TAKE_PROFIT","TECHNICAL_STOP","LOGIC_STOP"],"riskWeights":{"market":0.30,"sector":0.20,"technical":0.30,"capital":0.20},"missingEvidencePolicy":"DO_NOT_TRIGGER"}',
    '20260714-unified-1.1'
FROM user_account
WHERE deleted = 0;

UPDATE ai_research_schema_version
SET status = 'APPLIED', completed_at = CURRENT_TIMESTAMP(3)
WHERE version_no = '20260714-unified-1.1'
  AND status = 'APPLYING';

DROP TEMPORARY TABLE IF EXISTS tmp_ai_calendar_restore_guard;
DROP TEMPORARY TABLE IF EXISTS tmp_ai_trading_calendar_backup;
