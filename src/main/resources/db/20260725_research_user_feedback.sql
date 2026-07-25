-- User feedback is product-quality evidence only. It must not be consumed as a market label,
-- factor weight, strategy return, or model-training target.
CREATE TABLE IF NOT EXISTS ai_research_user_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    feedback_type VARCHAR(24) NOT NULL,
    comment VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ai_research_feedback_user_report_stock (user_id, report_id, stock_code),
    KEY idx_ai_research_feedback_report (user_id, report_id, updated_at DESC),
    CONSTRAINT fk_ai_research_feedback_user
        FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_ai_research_feedback_report
        FOREIGN KEY (report_id) REFERENCES ai_research_daily_report (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
