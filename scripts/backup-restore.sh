#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
BACKUP_ROOT="$ROOT_DIR/backups"

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
  - The script reads DATA_STORAGE_ROOT, DATA_STORAGE_ROOT_2, DATA_STORAGE_ROOT_3, ...
    from .env and backs up every configured shard/pool.
  - Postgres backup uses docker compose exec and requires the postgres containers
    to be running.
  - MinIO backup reads the host-mounted MinIO data directories.
  - Restore expects the stack to be stopped, then writes data back to the
    host-mounted paths.
USAGE
}

require_file() {
  local path="$1"
  if [ ! -f "$path" ]; then
    echo "Missing file: $path" >&2
    exit 1
  fi
}

load_env_value() {
  local key="$1"
  awk -F= -v key="$key" '
    $1 == key {
      sub(/^[[:space:]]+/, "", $2)
      sub(/[[:space:]]+$/, "", $2)
      print $2
      exit
    }
  ' "$ENV_FILE"
}

root_env_var_name() {
  local index="$1"
  if [ "$index" -eq 1 ]; then
    printf 'DATA_STORAGE_ROOT'
  else
    printf 'DATA_STORAGE_ROOT_%s' "$index"
  fi
}

collect_root_count() {
  local count=0
  while :; do
    local index=$((count + 1))
    local key
    key="$(root_env_var_name "$index")"
    local value
    value="$(load_env_value "$key")"
    if [ "$index" -eq 1 ]; then
      if [ -z "$value" ]; then
        echo "DATA_STORAGE_ROOT is not set in .env" >&2
        exit 1
      fi
    else
      if [ -z "$value" ]; then
        break
      fi
    fi
    count=$index
  done
  printf '%s' "$count"
}

compose() {
  (cd "$ROOT_DIR" && docker compose "$@")
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

postgres_service_name() {
  local index="$1"
  if [ "$index" -eq 1 ]; then
    printf 'postgres'
  else
    printf 'postgres-shard-%s' "$index"
  fi
}

minio_service_name() {
  local index="$1"
  if [ "$index" -eq 1 ]; then
    printf 'minio'
  else
    printf 'minio-%s' "$index"
  fi
}

backup_postgres() {
  local target_dir="$1"
  local root_count="$2"
  local index=1
  while (( index <= root_count )); do
    local service
    service="$(postgres_service_name "$index")"
    local postgres_container
    postgres_container="$(require_running_container "$service")"
    compose exec -T "$service" pg_dump \
      -U owldrive \
      -d owldrive \
      -Fc \
      -f /tmp/owldrive.dump
    docker cp "$postgres_container:/tmp/owldrive.dump" "$target_dir/${service}.dump"
    echo "Wrote $target_dir/${service}.dump"
    index=$((index + 1))
  done
}

backup_minio() {
  local target_dir="$1"
  local root_count="$2"
  local index=1
  while (( index <= root_count )); do
    local root_key
    root_key="$(root_env_var_name "$index")"
    local root_path
    root_path="$(load_env_value "$root_key")"
    local service
    service="$(minio_service_name "$index")"
    local service_dir="$root_path/$service"
    if [ ! -d "$service_dir" ]; then
      echo "MinIO data directory not found: $service_dir" >&2
      exit 1
    fi
    tar -czf "$target_dir/${service}.tgz" -C "$root_path" "$service"
    echo "Wrote $target_dir/${service}.tgz"
    index=$((index + 1))
  done
}

restore_postgres() {
  local backup_dir="$1"
  local root_count="$2"
  require_backup_dir "$backup_dir"
  local index=1
  while (( index <= root_count )); do
    local service
    service="$(postgres_service_name "$index")"
    local dump_file="$backup_dir/${service}.dump"
    if [ ! -f "$dump_file" ]; then
      echo "Missing Postgres dump: $dump_file" >&2
      exit 1
    fi
    if compose ps -q "$service" 2>/dev/null | grep -q .; then
      echo "Stop the stack before restoring Postgres." >&2
      exit 1
    fi
    compose up -d "$service"
    docker cp "$dump_file" "$(compose ps -q "$service"):/tmp/owldrive.dump"
    compose exec -T "$service" pg_restore \
      -U owldrive \
      -d owldrive \
      --clean \
      --if-exists \
      /tmp/owldrive.dump
    echo "Restored Postgres from $dump_file"
    index=$((index + 1))
  done
}

restore_minio() {
  local backup_dir="$1"
  local root_count="$2"
  require_backup_dir "$backup_dir"
  local index=1
  while (( index <= root_count )); do
    local service
    service="$(minio_service_name "$index")"
    local archive="$backup_dir/${service}.tgz"
    local root_key
    root_key="$(root_env_var_name "$index")"
    local root_path
    root_path="$(load_env_value "$root_key")"
    local service_dir="$root_path/$service"
    if [ ! -f "$archive" ]; then
      echo "Missing MinIO archive: $archive" >&2
      exit 1
    fi
    if compose ps -q "$service" 2>/dev/null | grep -q .; then
      echo "Stop the stack before restoring MinIO." >&2
      exit 1
    fi
    rm -rf "$service_dir"
    ensure_dir "$root_path"
    tar -xzf "$archive" -C "$root_path"
    echo "Restored MinIO from $archive"
    index=$((index + 1))
  done
}

main() {
  if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    usage
    exit 0
  fi

  require_file "$ENV_FILE"

  local root_count
  root_count="$(collect_root_count)"

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
          backup_postgres "$backup_dir" "$root_count"
          ;;
        minio)
          backup_minio "$backup_dir" "$root_count"
          ;;
        all)
          backup_postgres "$backup_dir" "$root_count"
          backup_minio "$backup_dir" "$root_count"
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
          restore_postgres "$backup_dir" "$root_count"
          ;;
        minio)
          restore_minio "$backup_dir" "$root_count"
          ;;
        all)
          restore_postgres "$backup_dir" "$root_count"
          restore_minio "$backup_dir" "$root_count"
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
