-- Run once on an existing maogou database before using register/login.
ALTER TABLE user_account ADD COLUMN phone VARCHAR(32) NULL AFTER email;
ALTER TABLE user_account ADD COLUMN password_hash VARCHAR(255) NULL AFTER phone;
ALTER TABLE user_account ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' AFTER password_hash;
ALTER TABLE user_account ADD COLUMN risk_preference VARCHAR(16) NULL AFTER status;
ALTER TABLE user_account ADD COLUMN last_login_at DATETIME NULL AFTER risk_preference;

CREATE UNIQUE INDEX uk_user_account_email ON user_account (email);
CREATE UNIQUE INDEX uk_user_account_phone ON user_account (phone);

UPDATE user_account
SET status = 'ACTIVE'
WHERE status IS NULL OR status = '';
