ALTER TABLE files
  ADD COLUMN IF NOT EXISTS storage_pool TEXT NOT NULL DEFAULT 'primary';

UPDATE files
SET storage_pool = COALESCE(NULLIF(storage_pool, ''), 'primary');

ALTER TABLE files
  ALTER COLUMN storage_pool SET DEFAULT 'primary';

CREATE INDEX IF NOT EXISTS idx_files_storage_pool ON files(storage_pool);
