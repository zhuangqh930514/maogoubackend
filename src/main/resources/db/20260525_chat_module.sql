-- Run once on an existing maogou database before using 猫狗畅聊.
CREATE TABLE IF NOT EXISTS ai_chat_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    model_name VARCHAR(128) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_chat_session_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    message_role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    model_name VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    error_message TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ai_chat_message_session_time (session_id, created_at),
    KEY idx_ai_chat_message_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_user_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    memory_summary TEXT NULL,
    last_interaction_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_user_memory_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
