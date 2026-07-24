CREATE TABLE IF NOT EXISTS ai_watchlist_analysis_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    prompt_template_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    active_key VARCHAR(64) NULL,
    total_count INT NOT NULL DEFAULT 0,
    completed_count INT NOT NULL DEFAULT 0,
    analyzed_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    current_stock_code VARCHAR(16) NULL,
    current_stock_name VARCHAR(64) NULL,
    message VARCHAR(500) NULL,
    last_error TEXT NULL,
    issue_details MEDIUMTEXT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_watchlist_analysis_active (active_key),
    KEY idx_watchlist_analysis_user_time (user_id, created_at, id),
    KEY idx_watchlist_analysis_status_time (status, updated_at),
    CONSTRAINT fk_watchlist_analysis_user
        FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_watchlist_analysis_prompt
        FOREIGN KEY (prompt_template_id) REFERENCES ai_prompt_template (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
