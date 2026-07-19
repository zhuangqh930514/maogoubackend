-- Point-in-time lineage for historical research-universe membership.
-- Apply once after 20260714_ai_research_unified.sql.

ALTER TABLE ai_research_universe_snapshot
    ADD COLUMN membership_source_name VARCHAR(64) NULL AFTER calendar_version,
    ADD COLUMN membership_source_revision VARCHAR(64) NULL AFTER membership_source_name,
    ADD COLUMN source_observed_at DATETIME(3) NULL AFTER membership_source_revision,
    ADD COLUMN point_in_time_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE' AFTER source_observed_at,
    ADD COLUMN point_in_time_reason VARCHAR(255) NULL AFTER point_in_time_status,
    ADD KEY idx_universe_snapshot_point_in_time
        (trade_date, point_in_time_status, status, quality_status);

ALTER TABLE ai_research_universe_item
    ADD COLUMN effective_to DATE NULL AFTER effective_from,
    ADD KEY idx_universe_item_effective
        (stock_code, effective_from, effective_to, included);
