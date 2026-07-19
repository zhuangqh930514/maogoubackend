-- M1 normalized industry-membership lineage for executable labels and frozen datasets.

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample_label ADD COLUMN sector_membership_fingerprint VARCHAR(128) NULL AFTER sector_excess_return',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'ai_sample_label'
      AND column_name = 'sector_membership_fingerprint'
);
PREPARE sector_membership_label_column_stmt FROM @ddl;
EXECUTE sector_membership_label_column_stmt;
DEALLOCATE PREPARE sector_membership_label_column_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample_label ADD INDEX idx_label_sector_evidence (label_version, is_current, sector_membership_fingerprint, label_available_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_sample_label'
      AND index_name = 'idx_label_sector_evidence'
);
PREPARE sector_membership_label_index_stmt FROM @ddl;
EXECUTE sector_membership_label_index_stmt;
DEALLOCATE PREPARE sector_membership_label_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_training_dataset_item ADD COLUMN sector_membership_fingerprint VARCHAR(128) NULL AFTER trading_state_fingerprint',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'ai_training_dataset_item'
      AND column_name = 'sector_membership_fingerprint'
);
PREPARE sector_membership_dataset_column_stmt FROM @ddl;
EXECUTE sector_membership_dataset_column_stmt;
DEALLOCATE PREPARE sector_membership_dataset_column_stmt;
