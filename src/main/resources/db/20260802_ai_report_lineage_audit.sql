-- 统一日报/报告血缘与结构化失败事实。
-- 历史报告不补造 pipeline、预测或策略关联，默认保持 LEGACY_UNVERIFIED。

SET @schema_name = DATABASE();

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_analysis_report ADD COLUMN pipeline_run_id BIGINT NULL AFTER strategy_release_id',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'ai_analysis_report'
      AND column_name = 'pipeline_run_id'
);
PREPARE ai_lineage_stmt FROM @ddl;
EXECUTE ai_lineage_stmt;
DEALLOCATE PREPARE ai_lineage_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_analysis_report ADD COLUMN lineage_status VARCHAR(32) NOT NULL DEFAULT ''LEGACY_UNVERIFIED'' AFTER pipeline_run_id',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'ai_analysis_report'
      AND column_name = 'lineage_status'
);
PREPARE ai_lineage_stmt FROM @ddl;
EXECUTE ai_lineage_stmt;
DEALLOCATE PREPARE ai_lineage_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_analysis_report ADD COLUMN lineage_issue_json MEDIUMTEXT NULL AFTER lineage_status',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'ai_analysis_report'
      AND column_name = 'lineage_issue_json'
);
PREPARE ai_lineage_stmt FROM @ddl;
EXECUTE ai_lineage_stmt;
DEALLOCATE PREPARE ai_lineage_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_analysis_report ADD COLUMN input_fingerprint VARCHAR(128) NULL AFTER lineage_issue_json',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'ai_analysis_report'
      AND column_name = 'input_fingerprint'
);
PREPARE ai_lineage_stmt FROM @ddl;
EXECUTE ai_lineage_stmt;
DEALLOCATE PREPARE ai_lineage_stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE ai_analysis_report ADD INDEX idx_analysis_report_pipeline_lineage (pipeline_run_id, lineage_status)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'ai_analysis_report'
      AND index_name = 'idx_analysis_report_pipeline_lineage'
);
PREPARE ai_lineage_stmt FROM @ddl;
EXECUTE ai_lineage_stmt;
DEALLOCATE PREPARE ai_lineage_stmt;

CREATE TABLE IF NOT EXISTS ai_pipeline_issue (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    pipeline_run_id BIGINT NOT NULL DEFAULT 0,
    trade_date DATE NOT NULL,
    step_key VARCHAR(96) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    stock_code VARCHAR(16) NOT NULL DEFAULT '',
    stock_name VARCHAR(64) NOT NULL DEFAULT '',
    provider_code VARCHAR(64) NOT NULL DEFAULT '',
    endpoint_type VARCHAR(96) NOT NULL DEFAULT '',
    reason_code VARCHAR(64) NOT NULL,
    reason_message VARCHAR(2048) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    next_retry_at DATETIME(3) NULL,
    recoverable TINYINT NOT NULL DEFAULT 0,
    source_as_of DATETIME(3) NULL,
    attempt_no INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ai_pipeline_issue_fact (
        user_id, report_id, pipeline_run_id, step_key, stock_code,
        provider_code, reason_code, attempt_no
    ),
    KEY idx_ai_pipeline_issue_report (user_id, report_id, created_at),
    KEY idx_ai_pipeline_issue_retry (recoverable, next_retry_at),
    KEY idx_ai_pipeline_issue_stock (user_id, stock_code, trade_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
