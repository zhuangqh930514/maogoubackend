-- Historical fast-start orchestration, evidence quarantine and artifact registry.
-- This migration is additive. It does not delete or rewrite existing research facts.

-- Bind generated data batches to the historical run that produced them. Live
-- batches stay NULL; readiness queries must never mix them with replay facts.
SET @schema_name = DATABASE();
SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_data_batch'
          AND column_name = 'backfill_run_id'
    ),
    'SELECT 1',
    'ALTER TABLE ai_data_batch ADD COLUMN backfill_run_id BIGINT NULL AFTER id'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'ai_data_batch'
          AND index_name = 'idx_data_batch_backfill_trade'
    ),
    'SELECT 1',
    'ALTER TABLE ai_data_batch ADD KEY idx_data_batch_backfill_trade (backfill_run_id, trade_date, sample_phase, status)'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS ai_historical_backfill_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pipeline_run_id BIGINT NULL,
    run_key VARCHAR(160) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    requested_start_date DATE NULL,
    requested_end_date DATE NOT NULL,
    effective_sample_start_date DATE NULL,
    effective_sample_end_date DATE NULL,
    target_trading_days INT NOT NULL,
    target_stocks_per_day INT NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    factor_version VARCHAR(64) NOT NULL,
    label_version VARCHAR(64) NOT NULL,
    calendar_version VARCHAR(64) NOT NULL,
    industry_standard VARCHAR(32) NOT NULL,
    source_manifest_checksum VARCHAR(128) NULL,
    run_config_json MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    current_stage VARCHAR(64) NULL,
    total_shards INT NOT NULL DEFAULT 0,
    succeeded_shards INT NOT NULL DEFAULT 0,
    quarantined_shards INT NOT NULL DEFAULT 0,
    failed_shards INT NOT NULL DEFAULT 0,
    readiness_snapshot_id BIGINT NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(3) NULL,
    last_heartbeat_at DATETIME(3) NULL,
    error_summary TEXT NULL,
    requested_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_historical_backfill_run_key (run_key),
    KEY idx_historical_backfill_run_status (status, lease_until, updated_at),
    KEY idx_historical_backfill_run_date (requested_end_date, label_version, status),
    KEY idx_historical_backfill_run_pipeline (pipeline_run_id),
    CONSTRAINT fk_historical_backfill_run_pipeline
        FOREIGN KEY (pipeline_run_id) REFERENCES ai_pipeline_run (id),
    CONSTRAINT fk_historical_backfill_run_operator
        FOREIGN KEY (requested_by) REFERENCES user_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- MySQL treats ROW_NUMBER as a reserved window-function name.  Rename the
-- column from the unreleased draft schema before any later repair statements
-- reference it.  Fresh installs skip this branch because the table is absent.
SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_data_quarantine'
          AND column_name = 'row_number'
    ) AND NOT EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_data_quarantine'
          AND column_name = 'source_row_number'
    ),
    'ALTER TABLE ai_data_quarantine CHANGE COLUMN `row_number` source_row_number BIGINT NULL',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS ai_historical_backfill_shard (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    backfill_run_id BIGINT NOT NULL,
    stage_key VARCHAR(64) NOT NULL,
    trade_date DATE NULL,
    bucket_no INT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_no INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    input_count INT NOT NULL DEFAULT 0,
    output_count INT NOT NULL DEFAULT 0,
    rejected_count INT NOT NULL DEFAULT 0,
    checkpoint_json MEDIUMTEXT NULL,
    input_fingerprint VARCHAR(128) NULL,
    output_fingerprint VARCHAR(128) NULL,
    provider_code VARCHAR(64) NULL,
    endpoint_type VARCHAR(96) NULL,
    next_retry_at DATETIME(3) NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(3) NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(2048) NULL,
    error_detail MEDIUMTEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_historical_backfill_shard_key (idempotency_key),
    UNIQUE KEY uk_historical_backfill_shard_position
        (backfill_run_id, stage_key, trade_date, bucket_no),
    KEY idx_historical_backfill_shard_claim (status, next_retry_at, lease_until),
    KEY idx_historical_backfill_shard_run (backfill_run_id, stage_key, status, trade_date),
    CONSTRAINT fk_historical_backfill_shard_run
        FOREIGN KEY (backfill_run_id) REFERENCES ai_historical_backfill_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_raw_evidence_manifest (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    backfill_run_id BIGINT NULL,
    provider_code VARCHAR(64) NOT NULL,
    dataset_code VARCHAR(96) NOT NULL,
    source_revision VARCHAR(128) NOT NULL,
    object_uri VARCHAR(1024) NOT NULL,
    object_size BIGINT NOT NULL DEFAULT 0,
    object_checksum VARCHAR(128) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    row_count BIGINT NOT NULL DEFAULT 0,
    range_start_date DATE NULL,
    range_end_date DATE NULL,
    observed_at DATETIME(3) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'STAGED',
    manifest_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_raw_evidence_object
        (provider_code, dataset_code, source_revision, object_checksum),
    KEY idx_raw_evidence_run (backfill_run_id, dataset_code, status),
    KEY idx_raw_evidence_range (dataset_code, range_start_date, range_end_date),
    CONSTRAINT fk_raw_evidence_run
        FOREIGN KEY (backfill_run_id) REFERENCES ai_historical_backfill_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_data_quarantine (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    backfill_run_id BIGINT NOT NULL,
    shard_id BIGINT NULL,
    provider_code VARCHAR(64) NOT NULL,
    dataset_code VARCHAR(96) NOT NULL,
    trade_date DATE NULL,
    stock_code VARCHAR(16) NULL,
    industry_code VARCHAR(32) NULL,
    source_row_number BIGINT NULL,
    field_name VARCHAR(96) NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_message VARCHAR(2048) NOT NULL,
    raw_fingerprint VARCHAR(128) NULL,
    quarantine_fingerprint VARCHAR(128) NOT NULL,
    retryable TINYINT NOT NULL DEFAULT 0,
    resolution_status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_at DATETIME(3) NULL,
    UNIQUE KEY uk_data_quarantine_fingerprint (backfill_run_id, quarantine_fingerprint),
    KEY idx_data_quarantine_run (backfill_run_id, resolution_status, reason_code),
    KEY idx_data_quarantine_stock (stock_code, trade_date, dataset_code),
    KEY idx_data_quarantine_retry (retryable, resolution_status, created_at),
    CONSTRAINT fk_data_quarantine_run
        FOREIGN KEY (backfill_run_id) REFERENCES ai_historical_backfill_run (id),
    CONSTRAINT fk_data_quarantine_shard
        FOREIGN KEY (shard_id) REFERENCES ai_historical_backfill_shard (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- The first version of this migration used nullable business columns in the
-- unique key. MySQL permits multiple NULL values in a unique key, so repair an
-- already-created table with an application-level immutable fingerprint.
--
-- The order below is intentional:
--   1. add the column as nullable so old rows can still be read;
--   2. backfill a deterministic fingerprint for every old row;
--   3. make duplicate legacy rows unique without deleting evidence;
--   4. only then enforce NOT NULL and the new unique index.
--
-- Do not move the NOT NULL/unique changes above the backfill. That makes an
-- upgrade fail as soon as the old table contains a NULL or duplicate fact.
SET @schema_name = DATABASE();
SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_data_quarantine'
          AND column_name = 'quarantine_fingerprint'
    ),
    'SELECT 1',
    'ALTER TABLE ai_data_quarantine ADD COLUMN quarantine_fingerprint VARCHAR(128) NULL AFTER raw_fingerprint'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE ai_data_quarantine
SET quarantine_fingerprint = LOWER(SHA2(CONCAT_WS('|',
    'QUARANTINE/1',
    COALESCE(backfill_run_id, ''),
    COALESCE(provider_code, ''),
    COALESCE(dataset_code, ''),
    COALESCE(DATE_FORMAT(trade_date, '%Y-%m-%d'), ''),
    COALESCE(stock_code, ''),
    COALESCE(industry_code, ''),
    COALESCE(source_row_number, ''),
    COALESCE(field_name, ''),
    COALESCE(reason_code, ''),
    COALESCE(raw_fingerprint, ''),
    COALESCE(reason_message, '')
), 256))
WHERE quarantine_fingerprint IS NULL OR quarantine_fingerprint = '';

-- Keep all historical rows. If a legacy unique key allowed the same fact more
-- than once, the first row keeps the deterministic business fingerprint and
-- later rows get an audit-only suffix based on their immutable primary key.
UPDATE ai_data_quarantine q
INNER JOIN (
    SELECT quarantine_fingerprint, MIN(id) AS keep_id
    FROM ai_data_quarantine
    WHERE quarantine_fingerprint IS NOT NULL
    GROUP BY quarantine_fingerprint
    HAVING COUNT(*) > 1
) duplicate_facts
    ON duplicate_facts.quarantine_fingerprint = q.quarantine_fingerprint
SET q.quarantine_fingerprint = CONCAT(q.quarantine_fingerprint, ':legacy:', q.id)
WHERE q.id <> duplicate_facts.keep_id;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'ai_data_quarantine'
          AND index_name = 'uk_data_quarantine_fact'
    ),
    'ALTER TABLE ai_data_quarantine DROP INDEX uk_data_quarantine_fact',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE ai_data_quarantine
    MODIFY COLUMN quarantine_fingerprint VARCHAR(128) NOT NULL;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'ai_data_quarantine'
          AND index_name = 'uk_data_quarantine_fingerprint'
    ),
    'SELECT 1',
    'ALTER TABLE ai_data_quarantine ADD UNIQUE KEY uk_data_quarantine_fingerprint (backfill_run_id, quarantine_fingerprint)'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS ai_training_readiness_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    backfill_run_id BIGINT NULL,
    pipeline_run_id BIGINT NULL,
    as_of_time DATETIME(3) NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    factor_version VARCHAR(64) NOT NULL,
    label_version VARCHAR(64) NOT NULL,
    calendar_version VARCHAR(64) NOT NULL,
    trading_days INT NOT NULL DEFAULT 0,
    stock_count INT NOT NULL DEFAULT 0,
    horizon_counts_json MEDIUMTEXT NOT NULL,
    regime_days_json MEDIUMTEXT NOT NULL,
    tradability_eligible INT NOT NULL DEFAULT 0,
    tradability_ready INT NOT NULL DEFAULT 0,
    tradability_coverage DECIMAL(10, 6) NOT NULL DEFAULT 0,
    universe_eligible INT NOT NULL DEFAULT 0,
    universe_ready INT NOT NULL DEFAULT 0,
    universe_coverage DECIMAL(10, 6) NOT NULL DEFAULT 0,
    sector_eligible INT NOT NULL DEFAULT 0,
    sector_ready INT NOT NULL DEFAULT 0,
    sector_coverage DECIMAL(10, 6) NOT NULL DEFAULT 0,
    feature_coverage_json MEDIUMTEXT NOT NULL,
    class_distribution_json MEDIUMTEXT NOT NULL,
    leakage_violation_count INT NOT NULL DEFAULT 0,
    duplicate_count INT NOT NULL DEFAULT 0,
    mock_source_count INT NOT NULL DEFAULT 0,
    stale_source_count INT NOT NULL DEFAULT 0,
    inferred_fact_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'INSUFFICIENT_DATA',
    blocking_gaps_json MEDIUMTEXT NOT NULL,
    evidence_checksum VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_training_readiness_evidence (evidence_checksum),
    KEY idx_training_readiness_status (status, as_of_time),
    KEY idx_training_readiness_version (label_version, as_of_time),
    KEY idx_training_readiness_run (backfill_run_id, created_at),
    CONSTRAINT fk_training_readiness_backfill
        FOREIGN KEY (backfill_run_id) REFERENCES ai_historical_backfill_run (id),
    CONSTRAINT fk_training_readiness_pipeline
        FOREIGN KEY (pipeline_run_id) REFERENCES ai_pipeline_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_artifact_package_registry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    package_type VARCHAR(32) NOT NULL,
    package_format VARCHAR(96) NOT NULL,
    package_version VARCHAR(32) NOT NULL,
    package_checksum VARCHAR(128) NOT NULL,
    signature_key_id VARCHAR(128) NULL,
    signature_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    source_schema_version VARCHAR(64) NULL,
    source_git_commit VARCHAR(64) NULL,
    preview_status VARCHAR(32) NOT NULL DEFAULT 'NOT_PREVIEWED',
    preview_token_hash VARCHAR(128) NULL,
    preview_expires_at DATETIME(3) NULL,
    import_status VARCHAR(32) NOT NULL DEFAULT 'NOT_IMPORTED',
    imported_by BIGINT NULL,
    imported_at DATETIME(3) NULL,
    manifest_json MEDIUMTEXT NOT NULL,
    validation_json MEDIUMTEXT NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_artifact_package_checksum (package_checksum),
    KEY idx_artifact_package_status (package_type, import_status, created_at),
    KEY idx_artifact_package_preview (preview_status, preview_expires_at),
    CONSTRAINT fk_artifact_package_operator
        FOREIGN KEY (imported_by) REFERENCES user_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
