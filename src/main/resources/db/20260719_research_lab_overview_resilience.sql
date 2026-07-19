-- Research lab overview resilience fixes.
-- 1) Add a covering index for mature-label counts used by the overview API.

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample_label ADD INDEX idx_label_status_current_overview (label_status, is_current)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_sample_label'
      AND index_name = 'idx_label_status_current_overview'
);
PREPARE overview_index_stmt FROM @ddl;
EXECUTE overview_index_stmt;
DEALLOCATE PREPARE overview_index_stmt;
