ALTER TABLE users DROP CONSTRAINT chk_users_role;

ALTER TABLE users
  ADD CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'OPERATIONS', 'USER'));

ALTER TABLE users DROP CONSTRAINT chk_users_quota;

ALTER TABLE users
  ADD CONSTRAINT chk_users_quota CHECK (
    (role = 'ADMIN' AND quota_bytes IS NULL)
    OR (role IN ('OPERATIONS', 'USER') AND quota_bytes IS NOT NULL AND quota_bytes >= 0)
  );
