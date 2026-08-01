-- 行情真实性契约：真实快照携带来源、交易日、数据语义和指纹。
-- 旧 market_snapshot 记录不补写推断的交易日或来源；它们只能作为未验证历史记录存在。
-- 本迁移可重复执行，适用于已有 MySQL 8 数据库。

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE market_snapshot ADD COLUMN trade_date DATE NULL AFTER quote_time',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'market_snapshot'
      AND column_name = 'trade_date'
);
PREPARE market_truth_stmt FROM @ddl;
EXECUTE market_truth_stmt;
DEALLOCATE PREPARE market_truth_stmt;

SET @ddl = 'ALTER TABLE market_snapshot MODIFY COLUMN change_amount DECIMAL(18, 4) NULL, MODIFY COLUMN change_percent DECIMAL(10, 4) NULL';
PREPARE market_truth_stmt FROM @ddl;
EXECUTE market_truth_stmt;
DEALLOCATE PREPARE market_truth_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE market_snapshot ADD COLUMN source_provider VARCHAR(64) NULL AFTER trade_date',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'market_snapshot'
      AND column_name = 'source_provider'
);
PREPARE market_truth_stmt FROM @ddl;
EXECUTE market_truth_stmt;
DEALLOCATE PREPARE market_truth_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE market_snapshot ADD COLUMN source_status VARCHAR(24) NULL AFTER source_provider',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'market_snapshot'
      AND column_name = 'source_status'
);
PREPARE market_truth_stmt FROM @ddl;
EXECUTE market_truth_stmt;
DEALLOCATE PREPARE market_truth_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE market_snapshot ADD COLUMN data_mode VARCHAR(24) NULL AFTER source_status',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'market_snapshot'
      AND column_name = 'data_mode'
);
PREPARE market_truth_stmt FROM @ddl;
EXECUTE market_truth_stmt;
DEALLOCATE PREPARE market_truth_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE market_snapshot ADD COLUMN source_fingerprint VARCHAR(128) NULL AFTER data_mode',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'market_snapshot'
      AND column_name = 'source_fingerprint'
);
PREPARE market_truth_stmt FROM @ddl;
EXECUTE market_truth_stmt;
DEALLOCATE PREPARE market_truth_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE market_snapshot ADD INDEX idx_market_snapshot_symbol_trade_quote (symbol, trade_date, quote_time)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'market_snapshot'
      AND index_name = 'idx_market_snapshot_symbol_trade_quote'
);
PREPARE market_truth_stmt FROM @ddl;
EXECUTE market_truth_stmt;
DEALLOCATE PREPARE market_truth_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE market_snapshot ADD UNIQUE KEY uk_market_snapshot_source_fact (symbol, source_provider, quote_time, source_fingerprint)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'market_snapshot'
      AND index_name = 'uk_market_snapshot_source_fact'
);
PREPARE market_truth_stmt FROM @ddl;
EXECUTE market_truth_stmt;
DEALLOCATE PREPARE market_truth_stmt;

CREATE TABLE IF NOT EXISTS market_quote_current (
    symbol VARCHAR(32) PRIMARY KEY,
    name VARCHAR(64) NULL,
    market VARCHAR(16) NULL,
    latest_price DECIMAL(18, 4) NOT NULL,
    change_amount DECIMAL(18, 4) NULL,
    change_percent DECIMAL(10, 4) NULL,
    volume_ratio DECIMAL(10, 4) NULL,
    amount DECIMAL(24, 4) NULL,
    trade_date DATE NULL,
    source_provider VARCHAR(64) NOT NULL,
    source_as_of DATETIME NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    source_status VARCHAR(24) NOT NULL,
    data_mode VARCHAR(24) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_market_quote_current_trade_source (trade_date, source_status, source_as_of),
    KEY idx_market_quote_current_provider_time (source_provider, source_as_of)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
