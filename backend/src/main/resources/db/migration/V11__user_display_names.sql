ALTER TABLE users
  ADD COLUMN display_name TEXT;

UPDATE users
SET display_name = COALESCE(
  NULLIF(TRIM(username), ''),
  NULLIF(split_part(email, '@', 1), ''),
  NULLIF(TRIM(keycloak_id), ''),
  id::text
);

ALTER TABLE users
  ALTER COLUMN display_name SET NOT NULL;

CREATE INDEX idx_users_display_name
  ON users(display_name);

ALTER TABLE user_access_logs
  ADD COLUMN display_name TEXT;

UPDATE user_access_logs l
SET display_name = COALESCE(
  (SELECT u.display_name FROM users u WHERE u.id = l.user_id),
  NULLIF(TRIM(l.email), ''),
  NULLIF(TRIM(l.keycloak_id), ''),
  'Anonymous'
);

ALTER TABLE user_access_logs
  ALTER COLUMN display_name SET NOT NULL;

CREATE INDEX idx_user_access_logs_display_name_created_at
  ON user_access_logs(display_name, created_at DESC);
