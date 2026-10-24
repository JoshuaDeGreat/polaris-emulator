-- Separate revocable website sessions; no changes to hotel login tickets or passwords.
CREATE TABLE IF NOT EXISTS website_sessions (
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id INT NOT NULL,
  security_version BIGINT NOT NULL,
  credential_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL,
  INDEX idx_website_session_user (user_id),
  INDEX idx_website_session_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
