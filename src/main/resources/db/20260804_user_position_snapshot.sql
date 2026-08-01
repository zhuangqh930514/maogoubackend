-- Persisted position state. It is rebuilt from trade_record and real market snapshots;
-- HTTP requests never need to aggregate the full trade history.
CREATE TABLE IF NOT EXISTS user_position_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NULL,
    quantity INT NOT NULL,
    average_cost DECIMAL(18, 4) NOT NULL,
    total_cost DECIMAL(20, 4) NOT NULL,
    realized_pnl DECIMAL(20, 4) NOT NULL DEFAULT 0,
    current_price DECIMAL(18, 4) NULL,
    market_value DECIMAL(20, 4) NULL,
    unrealized_pnl DECIMAL(20, 4) NULL,
    today_pnl DECIMAL(20, 4) NULL,
    today_pnl_rate DECIMAL(12, 4) NULL,
    quote_status VARCHAR(24) NOT NULL DEFAULT 'UNAVAILABLE',
    quote_source VARCHAR(64) NULL,
    quote_as_of DATETIME(3) NULL,
    calculation_status VARCHAR(24) NOT NULL DEFAULT 'UNAVAILABLE',
    unavailable_reason VARCHAR(512) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_position_snapshot_user_stock (user_id, stock_code),
    KEY idx_user_position_snapshot_user_status_value (user_id, calculation_status, market_value DESC),
    KEY idx_user_position_snapshot_user_updated (user_id, updated_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
