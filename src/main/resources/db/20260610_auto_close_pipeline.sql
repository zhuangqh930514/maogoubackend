-- Auto close AI learning pipeline switch and run status.

DROP PROCEDURE IF EXISTS maogou_add_column_if_missing;

DELIMITER //
CREATE PROCEDURE maogou_add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

CALL maogou_add_column_if_missing('ai_model_config', 'auto_close_pipeline_enabled', 'TINYINT NOT NULL DEFAULT 0 AFTER prompt_template');
CALL maogou_add_column_if_missing('ai_model_config', 'auto_close_pipeline_last_run_at', 'DATETIME NULL AFTER auto_close_pipeline_enabled');
CALL maogou_add_column_if_missing('ai_model_config', 'auto_close_pipeline_last_finished_at', 'DATETIME NULL AFTER auto_close_pipeline_last_run_at');
CALL maogou_add_column_if_missing('ai_model_config', 'auto_close_pipeline_last_status', 'VARCHAR(32) NOT NULL DEFAULT ''IDLE'' AFTER auto_close_pipeline_last_finished_at');
CALL maogou_add_column_if_missing('ai_model_config', 'auto_close_pipeline_last_message', 'TEXT NULL AFTER auto_close_pipeline_last_status');

DROP PROCEDURE IF EXISTS maogou_add_column_if_missing;
