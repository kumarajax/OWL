CREATE TABLE files (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  parent_folder_id UUID NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
  original_name VARCHAR(255) NOT NULL,
  storage_key TEXT NOT NULL,
  content_type VARCHAR(255),
  size_bytes BIGINT NOT NULL,
  checksum_sha256 VARCHAR(64) NOT NULL,
  deleted_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_files_unique_active_name_per_parent
  ON files(owner_id, parent_folder_id, lower(original_name))
  WHERE deleted_at IS NULL;

CREATE INDEX idx_files_owner ON files(owner_id);
CREATE INDEX idx_files_parent_folder ON files(parent_folder_id);
CREATE INDEX idx_files_owner_parent_folder ON files(owner_id, parent_folder_id);
CREATE INDEX idx_files_deleted_at ON files(deleted_at);
