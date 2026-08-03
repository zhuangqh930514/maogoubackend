-- Research-lab page indexes for large immutable evidence tables.
-- Safe to run repeatedly on an existing MySQL 8 database.

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_prediction ADD INDEX idx_prediction_lab_page (trade_date, id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_prediction'
      AND index_name = 'idx_prediction_lab_page'
);
PREPARE research_lab_index_stmt FROM @ddl;
EXECUTE research_lab_index_stmt;
DEALLOCATE PREPARE research_lab_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample_label ADD INDEX idx_label_lab_page (is_current, entry_trade_date, id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_sample_label'
      AND index_name = 'idx_label_lab_page'
);
PREPARE research_lab_index_stmt FROM @ddl;
EXECUTE research_lab_index_stmt;
DEALLOCATE PREPARE research_lab_index_stmt;
