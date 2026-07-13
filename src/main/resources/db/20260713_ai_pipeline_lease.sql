SET @schema_name = DATABASE();

SET @add_execution_owner = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_pipeline_run'
          AND column_name = 'execution_owner'
    ),
    'SELECT 1',
    'ALTER TABLE ai_pipeline_run ADD COLUMN execution_owner VARCHAR(64) NULL AFTER status'
);
PREPARE statement FROM @add_execution_owner;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_lease_until = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_pipeline_run'
          AND column_name = 'lease_until'
    ),
    'SELECT 1',
    'ALTER TABLE ai_pipeline_run ADD COLUMN lease_until DATETIME(3) NULL AFTER execution_owner'
);
PREPARE statement FROM @add_lease_until;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_lease_index = IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'ai_pipeline_run'
          AND index_name = 'idx_ai_pipeline_run_lease'
    ),
    'SELECT 1',
    'ALTER TABLE ai_pipeline_run ADD INDEX idx_ai_pipeline_run_lease (status, lease_until)'
);
PREPARE statement FROM @add_lease_index;
EXECUTE statement;
DEALLOCATE PREPARE statement;
