#!/usr/bin/env bash
set -u

ROOT_DIR="${OWL_ROOT_DIR:-/home/ajay/OWL_DRIVE/OWL}"
LOG_DIR="${OWL_STARTUP_LOG_DIR:-$HOME/.local/state/owl-drive}"
LOG_FILE="$LOG_DIR/startup.log"

mkdir -p "$LOG_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1

echo "============================================================"
echo "OWL Drive startup: $(date)"
echo "Repo: $ROOT_DIR"
echo "Log: $LOG_FILE"
echo "============================================================"

cd "$ROOT_DIR" || {
  echo "ERROR: unable to cd into $ROOT_DIR"
  exit 1
}

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT_DIR/.env"
  set +a
fi

storage_mount_ready() {
  if [ -z "${DATA_STORAGE_ROOT:-}" ]; then
    return 0
  fi

  [ -d "$DATA_STORAGE_ROOT" ] || return 1

  if command -v findmnt >/dev/null 2>&1; then
    local mount_target
    mount_target="$(findmnt -n -o TARGET -T "$DATA_STORAGE_ROOT" 2>/dev/null || true)"
    if [[ "$DATA_STORAGE_ROOT" == /run/media/* && "$mount_target" != /run/media/* ]]; then
      return 1
    fi
  fi

  return 0
}

wait_for_storage_mount() {
  if [ -z "${DATA_STORAGE_ROOT:-}" ]; then
    return 0
  fi

  local wait_seconds="${OWL_STORAGE_WAIT_SECONDS:-90}"
  local deadline=$((SECONDS + wait_seconds))

  echo
  echo "Checking OWL storage: $DATA_STORAGE_ROOT"
  while ! storage_mount_ready; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "ERROR: storage path is not mounted or not available: $DATA_STORAGE_ROOT" >&2
      echo "Refusing to start OWL because Postgres could initialize an empty database." >&2
      exit 1
    fi
    sleep 2
  done

  local postgres_dir="$DATA_STORAGE_ROOT/postgres"
  if [ ! -f "$postgres_dir/PG_VERSION" ] && [ "${OWL_ALLOW_EMPTY_STORAGE:-0}" != "1" ]; then
    echo "ERROR: expected existing Postgres data at $postgres_dir/PG_VERSION" >&2
    echo "Refusing to start with empty storage. Set OWL_ALLOW_EMPTY_STORAGE=1 only for a new install." >&2
    exit 1
  fi

  echo "  storage: ready"
}

wait_for_storage_mount

echo
echo "Cloudflare tunnel status:"
if command -v systemctl >/dev/null 2>&1; then
  if systemctl is-active --quiet cloudflared; then
    echo "  cloudflared: active"
  else
    echo "  cloudflared: not active. Check with: systemctl status cloudflared --no-pager"
  fi
else
  echo "  systemctl not found"
fi

echo
echo "Starting OWL Docker services..."
docker compose up -d postgres keycloak minio backend frontend

if [ -n "${KEYCLOAK_SEEDED_PASSWORD:-}" ] && [ -x "$ROOT_DIR/scripts/bootstrap-keycloak-passwords.sh" ]; then
  echo
  echo "Synchronizing seeded Keycloak passwords..."
  "$ROOT_DIR/scripts/bootstrap-keycloak-passwords.sh" || {
    echo "WARNING: unable to synchronize seeded Keycloak passwords"
  }
fi

echo
echo "Docker service status:"
docker compose ps

echo
echo "Local endpoint checks:"
if command -v curl >/dev/null 2>&1; then
  curl -fsS -o /dev/null http://localhost:3000 \
    && echo "  frontend local: ok http://localhost:3000" \
    || echo "  frontend local: not ready"
  curl -fsS -o /dev/null http://localhost:8081/api/public/registration \
    && echo "  backend local: ok http://localhost:8081/api/public/registration" \
    || echo "  backend local: not ready"
else
  echo "  curl not found; skipped endpoint checks"
fi

echo
echo "Public URLs:"
echo "  https://owl-drive.com"
echo "  https://api.owl-drive.com/api/public/registration"
echo "  https://auth.owl-drive.com/realms/owldrive"

echo
echo "Startup finished: $(date)"
echo "This window can stay open for status, or you can close it."
