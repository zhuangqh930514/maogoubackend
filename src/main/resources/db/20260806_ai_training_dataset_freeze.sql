-- Immutable training dataset freeze gate. Additive and safe for existing READY datasets.
SET @schema_name = DATABASE();

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_training_dataset'
          AND column_name = 'backfill_run_id'
    ),
    'SELECT 1',
    'ALTER TABLE ai_training_dataset ADD COLUMN backfill_run_id BIGINT NULL AFTER id'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_training_dataset'
          AND column_name = 'freeze_manifest_json'
    ),
    'SELECT 1',
    'ALTER TABLE ai_training_dataset ADD COLUMN freeze_manifest_json MEDIUMTEXT NULL AFTER finalized_at'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_training_dataset'
          AND column_name = 'freeze_checksum'
    ),
    'SELECT 1',
    'ALTER TABLE ai_training_dataset ADD COLUMN freeze_checksum VARCHAR(128) NULL AFTER freeze_manifest_json'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_training_dataset'
          AND column_name = 'frozen_at'
    ),
    'SELECT 1',
    'ALTER TABLE ai_training_dataset ADD COLUMN frozen_at DATETIME(3) NULL AFTER freeze_checksum'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_training_dataset'
          AND column_name = 'frozen_by'
    ),
    'SELECT 1',
    'ALTER TABLE ai_training_dataset ADD COLUMN frozen_by BIGINT NULL AFTER frozen_at'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'ai_training_dataset'
          AND index_name = 'idx_training_dataset_backfill_status'
    ),
    'SELECT 1',
    'ALTER TABLE ai_training_dataset ADD KEY idx_training_dataset_backfill_status (backfill_run_id, status, as_of_time)'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'ai_training_dataset'
          AND index_name = 'uk_training_dataset_freeze_checksum'
    ),
    'SELECT 1',
    'ALTER TABLE ai_training_dataset ADD UNIQUE KEY uk_training_dataset_freeze_checksum (freeze_checksum)'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
