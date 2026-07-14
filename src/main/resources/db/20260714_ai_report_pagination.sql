SET @schema_name = DATABASE();

SET @add_report_page_index = IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'ai_analysis_report'
          AND index_name = 'idx_ai_report_user_date_time'
    ),
    'SELECT 1',
    'ALTER TABLE ai_analysis_report ADD INDEX idx_ai_report_user_date_time (user_id, report_date, generated_at)'
);
PREPARE statement FROM @add_report_page_index;
EXECUTE statement;
DEALLOCATE PREPARE statement;
