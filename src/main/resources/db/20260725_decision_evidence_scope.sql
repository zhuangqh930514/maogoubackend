-- Preserve the scope of historical evidence. A strategy-level fallback must
-- never be presented to users as stock-specific verification.
-- MySQL 8 does not support PostgreSQL-style conditional column addition here, so
-- inspect INFORMATION_SCHEMA before issuing the forward-only DDL.
DELIMITER $$

DROP PROCEDURE IF EXISTS ensure_daily_decision_evidence_scope $$
CREATE PROCEDURE ensure_daily_decision_evidence_scope()
BEGIN
    DECLARE has_column INT DEFAULT 0;

    SELECT COUNT(*) INTO has_column
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_daily_decision_item'
      AND column_name = 'evidence_scope';

    IF has_column = 0 THEN
        ALTER TABLE ai_daily_decision_item
            ADD COLUMN evidence_scope VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'
            AFTER historical_hit_rate;
    END IF;
END $$

CALL ensure_daily_decision_evidence_scope() $$
DROP PROCEDURE ensure_daily_decision_evidence_scope $$

DELIMITER ;
