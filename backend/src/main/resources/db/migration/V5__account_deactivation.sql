ALTER TABLE users
  ADD COLUMN deactivated_at TIMESTAMPTZ;

CREATE INDEX idx_users_deactivated_at ON users(deactivated_at);
