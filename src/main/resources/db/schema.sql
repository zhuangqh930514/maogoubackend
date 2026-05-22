CREATE DATABASE IF NOT EXISTS maogou_stock
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE maogou_stock;

CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NULL,
    email VARCHAR(128) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_account_username (username)
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
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message TEXT NULL,
    generated_at DATETIME NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_report_user_time (user_id, generated_at),
    KEY idx_ai_report_stock_time (stock_code, generated_at)
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

INSERT IGNORE INTO user_account (id, username, display_name, email)
VALUES (1, 'demo', '默认用户', NULL);
