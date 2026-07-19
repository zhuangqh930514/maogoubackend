-- Complete executable-label outcomes and cost evidence for LABEL/1.1.0.

ALTER TABLE ai_sample_label
    ADD COLUMN planned_exit_trade_date DATE NULL AFTER entry_trade_date,
    ADD COLUMN exit_delay_trading_days INT NOT NULL DEFAULT 0 AFTER exit_trade_date,
    ADD COLUMN sector_excess_return DECIMAL(12, 6) NULL AFTER excess_return,
    ADD COLUMN max_drawdown DECIMAL(12, 6) NULL AFTER max_adverse_return,
    ADD COLUMN holding_volatility DECIMAL(12, 6) NULL AFTER max_drawdown,
    ADD COLUMN holding_trading_days INT NULL AFTER holding_volatility,
    ADD COLUMN fill_status VARCHAR(32) NOT NULL DEFAULT 'INVALID_SOURCE' AFTER actual_direction,
    ADD KEY idx_sample_label_fill_status
        (label_version, fill_status, execution_status, is_current);

ALTER TABLE ai_label_cost_evidence
    ADD COLUMN impact_cost_bps DECIMAL(12, 4) NULL AFTER slippage_bps,
    ADD COLUMN impact_cost_amount DECIMAL(20, 6) NULL AFTER slippage_amount;
