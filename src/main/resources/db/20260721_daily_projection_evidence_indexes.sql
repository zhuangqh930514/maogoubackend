SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_prediction ADD INDEX idx_prediction_strategy_trade_evidence (strategy_release_id, trade_date, id, stock_code, horizon_trading_days)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_prediction'
      AND index_name = 'idx_prediction_strategy_trade_evidence'
);
PREPARE evidence_index_stmt FROM @ddl;
EXECUTE evidence_index_stmt;
DEALLOCATE PREPARE evidence_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_prediction_evaluation ADD INDEX idx_prediction_evaluation_decision_summary (prediction_id, evaluation_status, direction_correct)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_prediction_evaluation'
      AND index_name = 'idx_prediction_evaluation_decision_summary'
);
PREPARE evidence_index_stmt FROM @ddl;
EXECUTE evidence_index_stmt;
DEALLOCATE PREPARE evidence_index_stmt;
