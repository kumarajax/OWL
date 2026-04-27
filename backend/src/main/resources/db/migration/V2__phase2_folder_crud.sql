ALTER TABLE folders
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN deleted_at TIMESTAMPTZ;

DROP INDEX IF EXISTS idx_folders_single_root_per_user;

CREATE UNIQUE INDEX idx_folders_single_root_per_user
  ON folders(owner_id)
  WHERE parent_id IS NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX idx_folders_unique_active_name_per_parent
  ON folders(owner_id, parent_id, lower(name))
  WHERE parent_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_folders_owner ON folders(owner_id);
CREATE INDEX idx_folders_parent ON folders(parent_id);
CREATE INDEX idx_folders_owner_parent_active ON folders(owner_id, parent_id)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_folders_deleted_at ON folders(deleted_at);
