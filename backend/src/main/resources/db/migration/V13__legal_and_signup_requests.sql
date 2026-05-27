ALTER TABLE users
  ADD COLUMN IF NOT EXISTS terms_version TEXT,
  ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS signup_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email TEXT NOT NULL,
  display_name TEXT,
  encrypted_password TEXT,
  password_nonce TEXT,
  status TEXT NOT NULL,
  legal_version TEXT NOT NULL,
  terms_accepted_at TIMESTAMPTZ NOT NULL,
  requester_ip TEXT,
  requester_user_agent TEXT,
  approve_token_hash TEXT NOT NULL,
  reject_token_hash TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  reviewed_at TIMESTAMPTZ,
  reviewed_action TEXT,
  review_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT signup_requests_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_signup_requests_pending_email
  ON signup_requests (lower(email))
  WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_signup_requests_approve_token_hash
  ON signup_requests (approve_token_hash);

CREATE INDEX IF NOT EXISTS idx_signup_requests_reject_token_hash
  ON signup_requests (reject_token_hash);
