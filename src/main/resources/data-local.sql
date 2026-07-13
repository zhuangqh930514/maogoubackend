MERGE INTO user_account (id, username, display_name, email, phone, password_hash, status, risk_preference, last_login_at, deleted, created_at, updated_at)
KEY (id)
VALUES (
  1357,
  'maogou-demo',
  '猫狗智投演示账号',
  'demo@maogou.local',
  '13570237470',
  '$2a$10$A1CwWsMBvLS56zCSfu1CsePi7MsR/0jLH..h2tomG6Ss1YHDrm8ZK',
  'ACTIVE',
  '均衡',
  CURRENT_TIMESTAMP(),
  0,
  CURRENT_TIMESTAMP(),
  CURRENT_TIMESTAMP()
);
