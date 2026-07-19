-- Support point-in-time sector benchmark stitching during executable label maturation.
-- Safe to run repeatedly on MySQL 8.

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_source_observation ADD INDEX idx_source_stock_type_quality_asof (stock_code, source_type, quality_status, as_of_time, id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'ai_source_observation'
      AND index_name = 'idx_source_stock_type_quality_asof'
);
PREPARE sector_label_index_stmt FROM @ddl;
EXECUTE sector_label_index_stmt;
DEALLOCATE PREPARE sector_label_index_stmt;
