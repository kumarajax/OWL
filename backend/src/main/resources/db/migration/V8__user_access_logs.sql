CREATE TABLE user_access_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  keycloak_id TEXT,
  email TEXT,
  ip_address TEXT,
  country TEXT,
  region TEXT,
  city TEXT,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  location_source TEXT,
  user_agent TEXT,
  method TEXT NOT NULL,
  path TEXT NOT NULL,
  status_code INT,
  duration_ms BIGINT NOT NULL,
  event_type TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_access_logs_created_at
  ON user_access_logs(created_at DESC);

CREATE INDEX idx_user_access_logs_user_id_created_at
  ON user_access_logs(user_id, created_at DESC);

CREATE INDEX idx_user_access_logs_email_created_at
  ON user_access_logs(email, created_at DESC);

CREATE INDEX idx_user_access_logs_ip_created_at
  ON user_access_logs(ip_address, created_at DESC);
