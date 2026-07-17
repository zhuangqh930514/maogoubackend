-- High-frequency list and point-in-time lookup indexes.
-- Safe to run repeatedly on an existing MySQL 8 database.

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE watch_stock ADD INDEX idx_watch_stock_user_list (user_id, deleted, priority ASC, created_at DESC)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'watch_stock'
      AND index_name = 'idx_watch_stock_user_list'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample ADD INDEX idx_sample_pending_labels (trade_date, stock_code, id, quality_status, tradable_status, source_fingerprint)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_sample'
      AND index_name = 'idx_sample_pending_labels'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample ADD INDEX idx_sample_training_readiness (quality_status, tradable_status, as_of_time, trade_date, stock_code, market_regime)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_sample'
      AND index_name = 'idx_sample_training_readiness'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample_label ADD INDEX idx_label_training_readiness (label_version, label_status, execution_status, horizon_trading_days, label_available_at, sample_id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_sample_label'
      AND index_name = 'idx_label_training_readiness'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE trade_record ADD INDEX idx_trade_record_user_active_stock (user_id, deleted, stock_code, traded_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'trade_record'
      AND index_name = 'idx_trade_record_user_active_stock'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE watch_stock ADD INDEX idx_watch_stock_user_group_list (user_id, deleted, group_name, priority ASC, created_at DESC)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'watch_stock'
      AND index_name = 'idx_watch_stock_user_group_list'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE trade_record ADD INDEX idx_trade_record_user_list (user_id, deleted, traded_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'trade_record'
      AND index_name = 'idx_trade_record_user_list'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample ADD INDEX idx_sample_analysis_lookup (stock_code, sample_phase, trade_date, as_of_time)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_sample'
      AND index_name = 'idx_sample_analysis_lookup'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_sample ADD INDEX idx_sample_lab_list (trade_date, id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_sample'
      AND index_name = 'idx_sample_lab_list'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_pipeline_run ADD INDEX idx_pipeline_owner_type_time (owner_user_id, scope_type, pipeline_type, created_at, id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_pipeline_run'
      AND index_name = 'idx_pipeline_owner_type_time'
);
PREPARE performance_index_stmt FROM @ddl;
EXECUTE performance_index_stmt;
DEALLOCATE PREPARE performance_index_stmt;
