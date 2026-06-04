-- Compatibility migration for early AI evolution prototype tables.
-- Fresh databases only need 20260601_ai_evolution_module.sql or schema.sql.
-- Existing databases that already have old ai_* tables should run this script once.

DROP PROCEDURE IF EXISTS maogou_add_column_if_missing;
DROP PROCEDURE IF EXISTS maogou_modify_column_if_exists;
DROP PROCEDURE IF EXISTS maogou_drop_index_if_exists;
DROP PROCEDURE IF EXISTS maogou_add_index_if_missing;

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

CREATE PROCEDURE maogou_modify_column_if_exists(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` MODIFY COLUMN `', p_column_name, '` ', p_column_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

CREATE PROCEDURE maogou_drop_index_if_exists(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND INDEX_NAME = p_index_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

CREATE PROCEDURE maogou_add_index_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_index_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND INDEX_NAME = p_index_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

-- Early prototype tables had verify_date as a required legacy column. Current code uses report_date/evaluated_at.
CALL maogou_modify_column_if_exists('ai_analysis_outcome', 'verify_date', 'DATE NULL DEFAULT NULL');
-- Early prototype tables had factor_category as a required legacy column. Current code uses factor_group.
CALL maogou_modify_column_if_exists('ai_analysis_factor_hit', 'factor_category', 'VARCHAR(32) NULL DEFAULT NULL');
-- Early prototype tables had name as a required legacy column. Current code uses title.
CALL maogou_modify_column_if_exists('ai_strategy_version', 'name', 'VARCHAR(128) NULL DEFAULT NULL');
-- Early prototype tables had event_type as a required legacy column. Current code uses action_type.
CALL maogou_modify_column_if_exists('ai_strategy_evolution_log', 'event_type', 'VARCHAR(32) NULL DEFAULT NULL');
-- Early prototype tables had title as a required legacy column. Current code uses action_summary.
CALL maogou_modify_column_if_exists('ai_strategy_evolution_log', 'title', 'VARCHAR(512) NULL DEFAULT NULL');

CALL maogou_add_column_if_missing('ai_analysis_outcome', 'stock_name', 'VARCHAR(64) NULL');
CALL maogou_add_column_if_missing('ai_analysis_outcome', 'report_date', 'DATE NULL');
CALL maogou_add_column_if_missing('ai_analysis_outcome', 'horizon_days', 'INT NOT NULL DEFAULT 1');
CALL maogou_add_column_if_missing('ai_analysis_outcome', 'actual_direction', 'VARCHAR(16) NOT NULL DEFAULT ''SIDEWAYS''');
CALL maogou_add_column_if_missing('ai_analysis_outcome', 'entry_price', 'DECIMAL(18, 4) NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_analysis_outcome', 'direction_correct', 'TINYINT NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_analysis_outcome', 'success', 'TINYINT NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_analysis_outcome', 'evaluated_at', 'DATETIME NULL');
CALL maogou_add_column_if_missing('ai_analysis_outcome', 'deleted', 'TINYINT NOT NULL DEFAULT 0');

CALL maogou_add_column_if_missing('ai_analysis_decision', 'decision', 'VARCHAR(16) NOT NULL DEFAULT ''WATCH''');
CALL maogou_add_column_if_missing('ai_analysis_decision', 'factors_json', 'MEDIUMTEXT NULL');
CALL maogou_add_column_if_missing('ai_analysis_decision', 'raw_decision_json', 'MEDIUMTEXT NULL');

CALL maogou_add_column_if_missing('ai_analysis_factor_hit', 'outcome_id', 'BIGINT NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_analysis_factor_hit', 'factor_group', 'VARCHAR(32) NOT NULL DEFAULT ''UNKNOWN''');
CALL maogou_add_column_if_missing('ai_analysis_factor_hit', 'direction', 'VARCHAR(16) NOT NULL DEFAULT ''NEUTRAL''');
CALL maogou_add_column_if_missing('ai_analysis_factor_hit', 'weight_score', 'DECIMAL(10, 4) NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_analysis_factor_hit', 'reason', 'VARCHAR(512) NULL');
CALL maogou_add_column_if_missing('ai_analysis_factor_hit', 'success_score', 'DECIMAL(10, 4) NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_analysis_factor_hit', 'pct_change', 'DECIMAL(10, 4) NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_analysis_factor_hit', 'market_regime', 'VARCHAR(32) NOT NULL DEFAULT ''未知''');

CALL maogou_add_column_if_missing('ai_factor_stat', 'factor_name', 'VARCHAR(128) NOT NULL DEFAULT ''UNKNOWN''');
CALL maogou_add_column_if_missing('ai_factor_stat', 'factor_group', 'VARCHAR(32) NOT NULL DEFAULT ''UNKNOWN''');
CALL maogou_add_column_if_missing('ai_factor_stat', 'sample_count', 'INT NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_factor_stat', 'weight_score', 'DECIMAL(10, 4) NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_factor_stat', 'last_evaluated_at', 'DATETIME NULL');

CALL maogou_add_column_if_missing('ai_strategy_version', 'title', 'VARCHAR(128) NOT NULL DEFAULT ''未命名策略''');
CALL maogou_add_column_if_missing('ai_strategy_version', 'avg_success_rate', 'DECIMAL(10, 4) NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_strategy_version', 'sample_count', 'INT NOT NULL DEFAULT 0');
CALL maogou_add_column_if_missing('ai_strategy_version', 'active', 'TINYINT NOT NULL DEFAULT 0');

CALL maogou_add_column_if_missing('ai_strategy_evolution_log', 'action_type', 'VARCHAR(32) NOT NULL DEFAULT ''UNKNOWN''');
CALL maogou_add_column_if_missing('ai_strategy_evolution_log', 'action_summary', 'VARCHAR(512) NULL');

-- The prototype unique key used report_id + holding_day. Current code verifies multiple horizons through horizon_days.
CALL maogou_drop_index_if_exists('ai_analysis_outcome', 'uk_ai_outcome_report_day');
CALL maogou_add_index_if_missing('ai_analysis_outcome', 'uk_ai_outcome_report_horizon', 'UNIQUE KEY `uk_ai_outcome_report_horizon` (`user_id`, `report_id`, `horizon_days`, `deleted`)');
CALL maogou_add_index_if_missing('ai_analysis_outcome', 'idx_ai_outcome_user_eval', 'KEY `idx_ai_outcome_user_eval` (`user_id`, `evaluated_at`)');
CALL maogou_add_index_if_missing('ai_analysis_outcome', 'idx_ai_outcome_stock_date', 'KEY `idx_ai_outcome_stock_date` (`stock_code`, `report_date`)');

DROP PROCEDURE IF EXISTS maogou_add_column_if_missing;
DROP PROCEDURE IF EXISTS maogou_modify_column_if_exists;
DROP PROCEDURE IF EXISTS maogou_drop_index_if_exists;
DROP PROCEDURE IF EXISTS maogou_add_index_if_missing;

UPDATE ai_analysis_outcome
SET stock_name = COALESCE(stock_name, stock_code),
    report_date = COALESCE(report_date, verify_date, DATE(created_at)),
    horizon_days = COALESCE(horizon_days, holding_day, 1),
    actual_direction = CASE
        WHEN pct_change > 0.3 THEN 'UP'
        WHEN pct_change < -0.3 THEN 'DOWN'
        ELSE 'SIDEWAYS'
    END,
    entry_price = COALESCE(NULLIF(entry_price, 0), open_price, 0),
    direction_correct = COALESCE(direction_correct, is_direction_correct, 0),
    success = COALESCE(success, is_success, 0),
    evaluated_at = COALESCE(evaluated_at, updated_at, created_at, NOW()),
    deleted = COALESCE(deleted, 0);

UPDATE ai_analysis_decision
SET decision = COALESCE(NULLIF(decision, ''), decision_action, 'WATCH');

UPDATE ai_analysis_factor_hit
SET factor_group = COALESCE(NULLIF(factor_group, ''), factor_category, 'UNKNOWN'),
    direction = CASE
        WHEN COALESCE(factor_weight_snapshot, 0) > 0 THEN 'POSITIVE'
        WHEN COALESCE(factor_weight_snapshot, 0) < 0 THEN 'NEGATIVE'
        ELSE COALESCE(NULLIF(direction, ''), 'NEUTRAL')
    END,
    weight_score = COALESCE(NULLIF(weight_score, 0), factor_weight_snapshot, 0),
    reason = COALESCE(reason, factor_reason),
    market_regime = COALESCE(NULLIF(market_regime, ''), '未知');

UPDATE ai_factor_stat
SET factor_name = COALESCE(NULLIF(factor_name, ''), factor_code),
    factor_group = COALESCE(NULLIF(factor_group, ''), 'UNKNOWN'),
    sample_count = COALESCE(NULLIF(sample_count, 0), hit_count, 0),
    weight_score = COALESCE(NULLIF(weight_score, 0), current_weight, recent_score, 0),
    last_evaluated_at = COALESCE(last_evaluated_at, last_updated_at, updated_at);

UPDATE ai_strategy_version
SET title = COALESCE(NULLIF(title, ''), name, version_no),
    avg_success_rate = COALESCE(NULLIF(avg_success_rate, 0), win_rate, 0),
    active = COALESCE(active, enabled, 0);

UPDATE ai_strategy_evolution_log
SET action_type = COALESCE(NULLIF(action_type, ''), event_type, 'UNKNOWN'),
    action_summary = COALESCE(action_summary, title, LEFT(description, 512));
