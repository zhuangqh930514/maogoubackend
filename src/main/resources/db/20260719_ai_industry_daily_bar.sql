-- M1 immutable point-in-time industry benchmark bars for executable sector-relative labels.

SET @schema_name = DATABASE();
SET @ddl = (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = @schema_name AND table_name = 'ai_research_universe_item') = 1
        AND
        (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = @schema_name AND table_name = 'ai_research_universe_item'
           AND column_name = 'industry_standard') = 0,
        'ALTER TABLE ai_research_universe_item ADD COLUMN industry_standard VARCHAR(32) NULL AFTER industry_name',
        'SELECT 1')
);
PREPARE industry_standard_stmt FROM @ddl;
EXECUTE industry_standard_stmt;
DEALLOCATE PREPARE industry_standard_stmt;

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
