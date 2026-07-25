-- Immutable source lineage for user-owned records that enter a research universe snapshot.
-- This migration is intentionally forward-only. Historical snapshots without captured lineage stay
-- "not recorded" and must not be judged from a source row's current deleted flag.
CREATE TABLE IF NOT EXISTS ai_research_universe_item_lineage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    universe_item_id BIGINT NOT NULL,
    source_type VARCHAR(48) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    source_record_id BIGINT NOT NULL,
    active_at_snapshot TINYINT NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_universe_item_lineage_source
        (universe_item_id, source_type, owner_user_id, source_record_id),
    KEY idx_universe_item_lineage_owner (owner_user_id, source_type, source_record_id),
    KEY idx_universe_item_lineage_active (universe_item_id, active_at_snapshot),
    CONSTRAINT chk_universe_item_lineage_active CHECK (active_at_snapshot IN (0, 1)),
    CONSTRAINT fk_universe_item_lineage_item
        FOREIGN KEY (universe_item_id) REFERENCES ai_research_universe_item (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
