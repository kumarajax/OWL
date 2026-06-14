#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/configure-keycloak-client.sh
  ./scripts/configure-keycloak-client.sh FRONTEND_URL BACKEND_URL KEYCLOAK_URL

Examples:
  ./scripts/configure-keycloak-client.sh
  ./scripts/configure-keycloak-client.sh \
    http://192.168.1.126:3000 \
    http://192.168.1.126:8081 \
    http://192.168.1.126:8080
  ./scripts/configure-keycloak-client.sh \
    https://owl-drive.com \
    https://api.owl-drive.com \
    https://auth.owl-drive.com

This updates:
  - Keycloak client redirect URIs, web origins, and logout redirect URIs
  - .env values used by Docker Compose for frontend/backend auth URLs

After changing URLs, rebuild/restart backend and frontend:
  docker compose up -d --build backend frontend
USAGE
}

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT_DIR/.env"
  set +a
fi

source "$ROOT_DIR/scripts/runtime-env.sh"
runtime_urls

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if [ "$#" -ne 0 ] && [ "$#" -ne 3 ]; then
  usage >&2
  exit 2
fi

normalize_origin() {
  local value="$1"
  value="${value%/}"
  case "$value" in
    http://*|https://*) ;;
    *)
      echo "URL must start with http:// or https://: $value" >&2
      exit 2
      ;;
  esac
  if printf '%s\n' "$value" | grep -q '/'; then
    local without_scheme="${value#http://}"
    without_scheme="${without_scheme#https://}"
    if printf '%s\n' "$without_scheme" | grep -q '/'; then
      echo "URL must be an origin without a path: $value" >&2
      exit 2
    fi
  fi
  printf '%s\n' "$value"
}

set_env_key() {
  local key="$1"
  local value="$2"
  local env_file="$ROOT_DIR/.env"
  touch "$env_file"
  if grep -q "^${key}=" "$env_file"; then
    python3 - "$env_file" "$key" "$value" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
key = sys.argv[2]
value = sys.argv[3]
lines = path.read_text(encoding="utf-8").splitlines()
updated = False
for index, line in enumerate(lines):
    if line.startswith(key + "="):
        lines[index] = f"{key}={value}"
        updated = True
if not updated:
    lines.append(f"{key}={value}")
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
  else
    printf '%s=%s\n' "$key" "$value" >> "$env_file"
  fi
}

if [ "$#" -eq 3 ]; then
  FRONTEND_PUBLIC_ORIGIN="$(normalize_origin "$1")"
  BACKEND_PUBLIC_ORIGIN="$(normalize_origin "$2")"
  KEYCLOAK_PUBLIC_ORIGIN="$(normalize_origin "$3")"
else
  FRONTEND_PUBLIC_ORIGIN="$(normalize_origin "${FRONTEND_PUBLIC_ORIGIN:-$FRONTEND_LAN_ORIGIN}")"
  BACKEND_PUBLIC_ORIGIN="$(normalize_origin "${BACKEND_PUBLIC_ORIGIN:-http://${OWL_HOST_IP_DETECTED}:${BACKEND_PORT}}")"
  KEYCLOAK_PUBLIC_ORIGIN="$(normalize_origin "${KEYCLOAK_PUBLIC_ORIGIN:-http://${OWL_HOST_IP_DETECTED}:${KEYCLOAK_PORT}}")"
fi

KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
CONTAINER="${KEYCLOAK_CONTAINER:-}"

if [ -z "$CONTAINER" ]; then
  CONTAINER="$(docker compose ps -q keycloak 2>/dev/null || true)"
fi

if [ -z "$CONTAINER" ]; then
  CONTAINER="owl-keycloak-1"
fi

redirect_uris_json=$(printf '["%s","%s/*","%s","%s/*","%s","%s/*"]' \
  "$FRONTEND_LOCAL_ORIGIN" "$FRONTEND_LOCAL_ORIGIN" \
  "$FRONTEND_LOOPBACK_ORIGIN" "$FRONTEND_LOOPBACK_ORIGIN" \
  "$FRONTEND_PUBLIC_ORIGIN" "$FRONTEND_PUBLIC_ORIGIN")
web_origins_json=$(printf '["%s","%s","%s"]' \
  "$FRONTEND_LOCAL_ORIGIN" "$FRONTEND_LOOPBACK_ORIGIN" "$FRONTEND_PUBLIC_ORIGIN")
logout_uris="${FRONTEND_LOCAL_ORIGIN}/*##${FRONTEND_LOOPBACK_ORIGIN}/*##${FRONTEND_PUBLIC_ORIGIN}/*"

cors_origins="${FRONTEND_LOCAL_ORIGIN},${FRONTEND_LOOPBACK_ORIGIN},${FRONTEND_PUBLIC_ORIGIN}"
allowed_issuers="${KEYCLOAK_LOCAL_ISSUER},${KEYCLOAK_LOOPBACK_ISSUER},${KEYCLOAK_PUBLIC_ORIGIN}/realms/${KEYCLOAK_REALM}"

set_env_key "NEXT_PUBLIC_API_BASE_URL" "$BACKEND_PUBLIC_ORIGIN"
set_env_key "NEXT_PUBLIC_KEYCLOAK_URL" "$KEYCLOAK_PUBLIC_ORIGIN"
set_env_key "APP_CORS_ALLOWED_ORIGINS" "$cors_origins"
set_env_key "APP_SECURITY_OAUTH2_JWT_ALLOWED_ISSUERS" "$allowed_issuers"

echo "Configuring Keycloak client $KEYCLOAK_CLIENT_ID for $FRONTEND_PUBLIC_ORIGIN ..."
echo "Updated .env for:"
echo "  Frontend: $FRONTEND_PUBLIC_ORIGIN"
echo "  Backend:  $BACKEND_PUBLIC_ORIGIN"
echo "  Keycloak: $KEYCLOAK_PUBLIC_ORIGIN"

for attempt in $(seq 1 30); do
  if docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
    --server "$KEYCLOAK_INTERNAL_URL" \
    --realm master \
    --user "$KEYCLOAK_ADMIN_USER" \
    --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1; then
    break
  fi

  if [ "$attempt" -eq 30 ]; then
    echo "Keycloak did not become ready in time." >&2
    exit 1
  fi

  sleep 2
done

docker exec "$CONTAINER" /bin/sh -c "
  set -e
  /opt/keycloak/bin/kcadm.sh update realms/'$KEYCLOAK_REALM' \
    -s 'sslRequired=none' \
    -s 'registrationAllowed=false'
  CLIENT_UUID=\$(/opt/keycloak/bin/kcadm.sh get clients -r '$KEYCLOAK_REALM' -q clientId='$KEYCLOAK_CLIENT_ID' --fields id --format csv --noquotes | tail -n 1)
  /opt/keycloak/bin/kcadm.sh update clients/\$CLIENT_UUID -r '$KEYCLOAK_REALM' \
    -s 'redirectUris=$redirect_uris_json' \
    -s 'webOrigins=$web_origins_json' \
    -s 'attributes.\"pkce.code.challenge.method\"=S256' \
    -s 'attributes.\"post.logout.redirect.uris\"=$logout_uris'
"

echo "Keycloak client ready for $FRONTEND_PUBLIC_ORIGIN"
echo "Rebuild/restart backend and frontend to apply .env changes:"
echo "  docker compose up -d --build backend frontend"
