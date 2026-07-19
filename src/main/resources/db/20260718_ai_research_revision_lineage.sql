-- AI research revision lineage for late-arriving or corrected market evidence.
-- Apply once after 20260714_ai_research_unified.sql.

ALTER TABLE ai_sample_label
    ADD COLUMN revision_no INT NOT NULL DEFAULT 1 AFTER label_version,
    ADD COLUMN is_current TINYINT NOT NULL DEFAULT 1 AFTER revision_no,
    ADD COLUMN supersedes_label_id BIGINT NULL AFTER is_current,
    ADD COLUMN revision_reason VARCHAR(64) NULL AFTER supersedes_label_id;

ALTER TABLE ai_sample_label
    DROP INDEX uk_sample_label_version,
    ADD UNIQUE KEY uk_sample_label_version
        (sample_id, horizon_trading_days, label_version, revision_no),
    ADD KEY idx_sample_label_current
        (sample_id, horizon_trading_days, label_version, is_current),
    ADD CONSTRAINT fk_sample_label_supersedes
        FOREIGN KEY (supersedes_label_id) REFERENCES ai_sample_label (id);

ALTER TABLE ai_factor_performance
    ADD COLUMN revision_no INT NOT NULL DEFAULT 1 AFTER window_end_date,
    ADD COLUMN is_current TINYINT NOT NULL DEFAULT 1 AFTER revision_no,
    ADD COLUMN supersedes_performance_id BIGINT NULL AFTER is_current,
    ADD COLUMN revision_reason VARCHAR(64) NULL AFTER supersedes_performance_id;

ALTER TABLE ai_factor_performance
    DROP INDEX uk_factor_performance_window,
    ADD UNIQUE KEY uk_factor_performance_window
        (factor_definition_id, horizon_trading_days, market_regime, window_type,
         window_start_date, window_end_date, revision_no),
    ADD KEY idx_factor_performance_current
        (factor_definition_id, horizon_trading_days, market_regime, window_type,
         window_start_date, window_end_date, is_current),
    ADD CONSTRAINT fk_factor_performance_supersedes
        FOREIGN KEY (supersedes_performance_id) REFERENCES ai_factor_performance (id);

ALTER TABLE ai_pipeline_run
    ADD COLUMN error_detail MEDIUMTEXT NULL AFTER error_message;

ALTER TABLE ai_pipeline_step
    ADD COLUMN error_detail MEDIUMTEXT NULL AFTER error_message;
