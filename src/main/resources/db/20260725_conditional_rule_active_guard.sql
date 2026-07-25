-- A conditional trade-rule promotion must leave exactly one ACTIVE rule per user.
-- The service locks the Shadow, candidate and baseline rows; this guard also blocks
-- accidental or future write paths from creating two simultaneous formal rules.
DELIMITER $$

DROP PROCEDURE IF EXISTS ensure_conditional_rule_active_guard $$
CREATE PROCEDURE ensure_conditional_rule_active_guard()
BEGIN
    DECLARE duplicate_count BIGINT DEFAULT 0;
    DECLARE has_column INT DEFAULT 0;
    DECLARE has_index INT DEFAULT 0;

    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT user_id
        FROM ai_trade_rule_config
        WHERE status = 'ACTIVE'
        GROUP BY user_id
        HAVING COUNT(*) > 1
    ) duplicates;
    IF duplicate_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Cannot add conditional rule active guard: multiple ACTIVE rules exist for one or more users';
    END IF;

    SELECT COUNT(*) INTO has_column
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_trade_rule_config'
      AND column_name = 'active_guard';
    IF has_column = 0 THEN
        ALTER TABLE ai_trade_rule_config
            ADD COLUMN active_guard TINYINT GENERATED ALWAYS AS (
                CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
            ) STORED;
    END IF;

    SELECT COUNT(*) INTO has_index
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_trade_rule_config'
      AND index_name = 'uk_trade_rule_config_single_active';
    IF has_index = 0 THEN
        ALTER TABLE ai_trade_rule_config
            ADD UNIQUE KEY uk_trade_rule_config_single_active (user_id, active_guard);
    END IF;
END $$

CALL ensure_conditional_rule_active_guard() $$
DROP PROCEDURE ensure_conditional_rule_active_guard $$

DELIMITER ;
