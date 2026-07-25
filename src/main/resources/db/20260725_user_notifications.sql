-- Persistent user-visible notifications for daily research results and recoverable warnings.
-- Safe to apply to an existing MySQL 8 production schema before deploying the application.
CREATE TABLE IF NOT EXISTS ai_user_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    notification_type VARCHAR(48) NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    level VARCHAR(16) NOT NULL DEFAULT 'INFO',
    title VARCHAR(160) NOT NULL,
    content VARCHAR(1024) NOT NULL,
    report_id BIGINT NULL,
    trade_date DATE NULL,
    is_read TINYINT NOT NULL DEFAULT 0,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ai_user_notification_dedupe (user_id, dedupe_key),
    KEY idx_ai_user_notification_recent (user_id, is_read, created_at DESC),
    KEY idx_ai_user_notification_report (user_id, report_id),
    CONSTRAINT fk_ai_user_notification_user
        FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_ai_user_notification_report
        FOREIGN KEY (report_id) REFERENCES ai_research_daily_report (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
