-- M1 point-in-time daily tradability facts. This migration is forward-only.

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
