#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_ROOT="$ROOT_DIR/backups"
PEN_ROOT="${PEN_ROOT:-/Volumes/PEN/OWL_DRIVE}"
POSTGRES_DIR="${POSTGRES_DIR:-$PEN_ROOT/postgres}"
MINIO_DIR="${MINIO_DIR:-$PEN_ROOT/minio}"

compose() {
  (cd "$ROOT_DIR" && docker compose "$@")
}

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/backup-restore.sh backup [postgres|minio|all] [backup-name]
  ./scripts/backup-restore.sh restore [postgres|minio|all] <backup-dir>

Examples:
  ./scripts/backup-restore.sh backup all
  ./scripts/backup-restore.sh backup postgres my-db-backup
  ./scripts/backup-restore.sh restore all backups/backup-20260513-220523

Notes:
  - Postgres backup uses docker compose exec and requires the postgres container to be running.
  - MinIO backup reads the host-mounted MinIO data directory.
  - Restore expects the stack to be stopped, then writes data back to the host-mounted paths.
USAGE
}

timestamp() {
  date +%Y%m%d-%H%M%S
}

ensure_dir() {
  local path="$1"
  mkdir -p "$path"
}

require_backup_dir() {
  local path="$1"
  if [ ! -d "$path" ]; then
    echo "Backup directory not found: $path" >&2
    exit 1
  fi
}

require_running_container() {
  local service="$1"
  local container_id
  container_id="$(compose ps -q "$service" 2>/dev/null || true)"
  if [ -z "$container_id" ]; then
    echo "$service container is not running. Start the stack first." >&2
    exit 1
  fi
  printf '%s' "$container_id"
}

backup_postgres() {
  local target_dir="$1"
  local postgres_container
  postgres_container="$(require_running_container postgres)"

  compose exec -T postgres pg_dump \
    -U owldrive \
    -d owldrive \
    -Fc \
    -f /tmp/owldrive.dump

  docker cp "$postgres_container:/tmp/owldrive.dump" "$target_dir/owldrive.dump"
  echo "Wrote $target_dir/owldrive.dump"
}

backup_minio() {
  local target_dir="$1"
  if [ ! -d "$MINIO_DIR" ]; then
    echo "MinIO data directory not found: $MINIO_DIR" >&2
    exit 1
  fi

  tar -czf "$target_dir/minio.tgz" -C "$PEN_ROOT" minio
  echo "Wrote $target_dir/minio.tgz"
}

restore_postgres() {
  local backup_dir="$1"
  local dump_file="$backup_dir/owldrive.dump"
  require_backup_dir "$backup_dir"
  if [ ! -f "$dump_file" ]; then
    echo "Missing Postgres dump: $dump_file" >&2
    exit 1
  fi

  if compose ps -q postgres 2>/dev/null | grep -q .; then
    echo "Stop the stack before restoring Postgres." >&2
    exit 1
  fi

  ensure_dir "$POSTGRES_DIR"
  compose up -d postgres
  docker cp "$dump_file" "$(compose ps -q postgres):/tmp/owldrive.dump"
  compose exec -T postgres pg_restore \
    -U owldrive \
    -d owldrive \
    --clean \
    --if-exists \
    /tmp/owldrive.dump
  echo "Restored Postgres from $dump_file"
}

restore_minio() {
  local backup_dir="$1"
  local archive="$backup_dir/minio.tgz"
  require_backup_dir "$backup_dir"
  if [ ! -f "$archive" ]; then
    echo "Missing MinIO archive: $archive" >&2
    exit 1
  fi

  if compose ps -q minio 2>/dev/null | grep -q .; then
    echo "Stop the stack before restoring MinIO." >&2
    exit 1
  fi

  rm -rf "$MINIO_DIR"
  ensure_dir "$MINIO_DIR"
  tar -xzf "$archive" -C "$PEN_ROOT"
  echo "Restored MinIO from $archive"
}

main() {
  if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    usage
    exit 0
  fi

  local action="${1:-}"
  local target="${2:-all}"
  local backup_name="${3:-}"

  case "$action" in
    backup)
      if [ -z "$backup_name" ]; then
        backup_name="backup-$(timestamp)"
      fi
      local backup_dir="$BACKUP_ROOT/$backup_name"
      ensure_dir "$backup_dir"
      case "$target" in
        postgres)
          backup_postgres "$backup_dir"
          ;;
        minio)
          backup_minio "$backup_dir"
          ;;
        all)
          backup_postgres "$backup_dir"
          backup_minio "$backup_dir"
          ;;
        *)
          usage >&2
          exit 2
          ;;
      esac
      ;;
    restore)
      local backup_dir="${3:-}"
      if [ -z "$backup_dir" ]; then
        usage >&2
        exit 2
      fi
      case "$target" in
        postgres)
          restore_postgres "$backup_dir"
          ;;
        minio)
          restore_minio "$backup_dir"
          ;;
        all)
          restore_postgres "$backup_dir"
          restore_minio "$backup_dir"
          ;;
        *)
          usage >&2
          exit 2
          ;;
      esac
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
