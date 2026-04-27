ALTER TABLE users
  ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER',
  ADD COLUMN quota_bytes BIGINT,
  ADD COLUMN used_bytes BIGINT NOT NULL DEFAULT 0;

UPDATE users
SET role = 'USER',
    quota_bytes = 2147483648,
    used_bytes = 0
WHERE role = 'USER';

ALTER TABLE users
  ADD CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'USER')),
  ADD CONSTRAINT chk_users_quota CHECK (
    (role = 'ADMIN' AND quota_bytes IS NULL)
    OR (role = 'USER' AND quota_bytes IS NOT NULL AND quota_bytes >= 0)
  ),
  ADD CONSTRAINT chk_users_used_bytes CHECK (used_bytes >= 0);
