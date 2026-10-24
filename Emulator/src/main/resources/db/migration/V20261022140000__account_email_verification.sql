-- Account email ownership and per-account recovery cooldowns.
-- Nullable verified_at deliberately preserves existing accounts without claiming verification.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at DATETIME NULL DEFAULT NULL;
CREATE TABLE IF NOT EXISTS email_verification_tokens (
  user_id INT NOT NULL PRIMARY KEY,
  email VARCHAR(254) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY idx_email_verification_token (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS password_recovery_limits (
  user_id INT NOT NULL PRIMARY KEY,
  next_allowed_at DATETIME NOT NULL,
  INDEX idx_password_recovery_limit_expiry (next_allowed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
