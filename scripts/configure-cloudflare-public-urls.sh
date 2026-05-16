#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/configure-cloudflare-public-urls.sh FRONTEND_URL BACKEND_URL KEYCLOAK_URL

Example:
  ./scripts/configure-cloudflare-public-urls.sh \
    https://frontend.trycloudflare.com \
    https://api.trycloudflare.com \
    https://auth.trycloudflare.com

This updates:
  - docker-compose.yml
  - frontend/Dockerfile
  - frontend/.env.local

Optional:
  KEYCLOAK_ADMIN_PASSWORD='your-password' ./scripts/configure-cloudflare-public-urls.sh FRONTEND_URL BACKEND_URL KEYCLOAK_URL

  When KEYCLOAK_ADMIN_PASSWORD is set, the script also updates the Keycloak
  owl-drive-web client redirect URIs, web origins, and logout redirect URIs.

After it succeeds, it rebuilds the frontend image and restarts backend/frontend:
  docker compose build --no-cache frontend
  docker compose up -d backend frontend
USAGE
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if [ "$#" -ne 3 ]; then
  usage >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT_DIR/.env"
  set +a
fi
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
DOCKERFILE="$ROOT_DIR/frontend/Dockerfile"
FRONTEND_ENV_FILE="$ROOT_DIR/frontend/.env.local"

normalize_url() {
  local value="$1"
  value="${value%/}"
  case "$value" in
    https://*) printf '%s\n' "$value" ;;
    *)
      echo "URL must start with https://: $value" >&2
      exit 2
      ;;
  esac
}

FRONTEND_URL="$(normalize_url "$1")"
BACKEND_URL="$(normalize_url "$2")"
KEYCLOAK_URL="$(normalize_url "$3")"

python3 - "$COMPOSE_FILE" "$DOCKERFILE" "$FRONTEND_ENV_FILE" "$FRONTEND_URL" "$BACKEND_URL" "$KEYCLOAK_URL" <<'PY'
from pathlib import Path
import re
import sys
from urllib.parse import urlparse

compose_path = Path(sys.argv[1])
dockerfile_path = Path(sys.argv[2])
frontend_env_path = Path(sys.argv[3])
frontend_url = sys.argv[4]
backend_url = sys.argv[5]
keycloak_url = sys.argv[6]

for label, value in [
    ("frontend", frontend_url),
    ("backend", backend_url),
    ("keycloak", keycloak_url),
]:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.path not in ("", "/"):
        raise SystemExit(f"{label} URL must be an origin like https://host.example: {value}")

compose = compose_path.read_text(encoding="utf-8")
dockerfile = dockerfile_path.read_text(encoding="utf-8")
frontend_env = frontend_env_path.read_text(encoding="utf-8") if frontend_env_path.exists() else ""

issuer_value = (
    f"{keycloak_url.replace('https://', 'http://')}/realms/owldrive,"
    f"{keycloak_url}/realms/owldrive,"
    "http://localhost:8080/realms/owldrive"
)

def replace_key(text: str, key: str, value: str) -> tuple[str, bool]:
    pattern = re.compile(rf"^(\s+{re.escape(key)}:\s*).*$", re.MULTILINE)
    replacement = rf"\g<1>{value}"
    text, count = pattern.subn(replacement, text)
    return text, count > 0

def replace_env_key(text: str, key: str, value: str) -> str:
    pattern = re.compile(rf"^{re.escape(key)}=.*$", re.MULTILINE)
    replacement = f"{key}={value}"
    text, count = pattern.subn(replacement, text)
    if count:
        return text
    if text and not text.endswith("\n"):
        text += "\n"
    return text + replacement + "\n"

compose, found_cors = replace_key(compose, "APP_CORS_ALLOWED_ORIGINS", frontend_url)
compose, found_issuers = replace_key(compose, "APP_SECURITY_OAUTH2_JWT_ALLOWED_ISSUERS", issuer_value)
compose, found_api_env = replace_key(compose, "NEXT_PUBLIC_API_BASE_URL", backend_url)
compose, found_keycloak_env = replace_key(compose, "NEXT_PUBLIC_KEYCLOAK_URL", keycloak_url)

if not found_cors:
    compose = compose.replace(
        "      APP_STORAGE_MINIO_BUCKET: owl-drive\n",
        "      APP_STORAGE_MINIO_BUCKET: owl-drive\n"
        f"      APP_CORS_ALLOWED_ORIGINS: {frontend_url}\n",
    )

if not found_issuers:
    compose = compose.replace(
        f"      APP_CORS_ALLOWED_ORIGINS: {frontend_url}\n",
        f"      APP_CORS_ALLOWED_ORIGINS: {frontend_url}\n"
        f"      APP_SECURITY_OAUTH2_JWT_ALLOWED_ISSUERS: {issuer_value}\n",
    )

if not found_api_env or not found_keycloak_env:
    raise SystemExit("Unable to find frontend NEXT_PUBLIC_* environment keys in docker-compose.yml")

# Repair the common broken form where an issuer value was split onto the next line.
compose = re.sub(
    r"(^\s+APP_SECURITY_OAUTH2_JWT_ALLOWED_ISSUERS:\s*)\n\s*https?://[^\n]+",
    rf"\g<1>{issuer_value}",
    compose,
    flags=re.MULTILINE,
)

frontend_build_pattern = re.compile(
    r"(?ms)^  frontend:\n"
    r"(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:|\Z)"
)
match = frontend_build_pattern.search(compose)
if not match:
    raise SystemExit("Unable to find frontend service in docker-compose.yml")

frontend_block = match.group(0)
if "    build:\n      context: ./frontend\n" not in frontend_block:
    raise SystemExit("Unable to find frontend build context in docker-compose.yml")

if "      args:\n" not in frontend_block:
    frontend_block = frontend_block.replace(
        "    build:\n      context: ./frontend\n",
        "    build:\n      context: ./frontend\n"
        "      args:\n"
        f"        NEXT_PUBLIC_API_BASE_URL: {backend_url}\n"
        f"        NEXT_PUBLIC_KEYCLOAK_URL: {keycloak_url}\n",
    )
else:
    if "        NEXT_PUBLIC_API_BASE_URL:" in frontend_block:
        frontend_block = re.sub(
            r"^(\s+NEXT_PUBLIC_API_BASE_URL:\s*).*$",
            rf"\g<1>{backend_url}",
            frontend_block,
            flags=re.MULTILINE,
        )
    else:
        frontend_block = frontend_block.replace(
            "      args:\n",
            "      args:\n"
            f"        NEXT_PUBLIC_API_BASE_URL: {backend_url}\n",
        )
    if "        NEXT_PUBLIC_KEYCLOAK_URL:" in frontend_block:
        frontend_block = re.sub(
            r"^(\s+NEXT_PUBLIC_KEYCLOAK_URL:\s*).*$",
            rf"\g<1>{keycloak_url}",
            frontend_block,
            flags=re.MULTILINE,
        )
    else:
        frontend_block = frontend_block.replace(
            "      args:\n",
            "      args:\n"
            f"        NEXT_PUBLIC_KEYCLOAK_URL: {keycloak_url}\n",
        )

compose = compose[: match.start()] + frontend_block + compose[match.end() :]

required_dockerfile_block = (
    "ARG NEXT_PUBLIC_API_BASE_URL\n"
    "ARG NEXT_PUBLIC_KEYCLOAK_URL\n"
    "ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL\n"
    "ENV NEXT_PUBLIC_KEYCLOAK_URL=$NEXT_PUBLIC_KEYCLOAK_URL\n"
)
if "ARG NEXT_PUBLIC_API_BASE_URL" not in dockerfile:
    dockerfile = dockerfile.replace(
        "COPY . .\nRUN npm run build\n",
        "COPY . .\n" + required_dockerfile_block + "RUN npm run build\n",
    )
else:
    for line in required_dockerfile_block.splitlines():
        if line not in dockerfile:
            dockerfile = dockerfile.replace("RUN npm run build\n", line + "\nRUN npm run build\n")

frontend_env = replace_env_key(frontend_env, "NEXT_PUBLIC_API_BASE_URL", backend_url)
frontend_env = replace_env_key(frontend_env, "NEXT_PUBLIC_KEYCLOAK_URL", keycloak_url)

compose_path.write_text(compose, encoding="utf-8")
dockerfile_path.write_text(dockerfile, encoding="utf-8")
frontend_env_path.write_text(frontend_env, encoding="utf-8")
PY

cd "$ROOT_DIR"
docker compose config >/dev/null

if [ -n "${KEYCLOAK_ADMIN_PASSWORD:-}" ]; then
  KEYCLOAK_CONTAINER="${KEYCLOAK_CONTAINER:-owl-keycloak-1}"
  KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
  KEYCLOAK_REALM="${KEYCLOAK_REALM:-owldrive}"
  KEYCLOAK_CLIENT_ID="${KEYCLOAK_CLIENT_ID:-owl-drive-web}"

  docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
    --server http://localhost:8080 \
    --realm master \
    --user "$KEYCLOAK_ADMIN_USER" \
    --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

  CLIENT_UUID="$(docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh get clients \
    -r "$KEYCLOAK_REALM" \
    -q clientId="$KEYCLOAK_CLIENT_ID" \
    --fields id \
    --format csv \
    --noquotes | tail -n 1)"

  if [ -z "$CLIENT_UUID" ]; then
    echo "Unable to find Keycloak client: $KEYCLOAK_CLIENT_ID" >&2
    exit 1
  fi

  docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh update "clients/$CLIENT_UUID" \
    -r "$KEYCLOAK_REALM" \
    -s "redirectUris=[\"http://localhost:3000\",\"http://localhost:3000/*\",\"$FRONTEND_URL\",\"$FRONTEND_URL/*\"]" \
    -s "webOrigins=[\"http://localhost:3000\",\"$FRONTEND_URL\"]" \
    -s "attributes.\"post.logout.redirect.uris\"=\"http://localhost:3000/*##$FRONTEND_URL/*\""

  KEYCLOAK_STATUS="Updated Keycloak client $KEYCLOAK_CLIENT_ID in container $KEYCLOAK_CONTAINER."
else
  KEYCLOAK_STATUS="Skipped Keycloak client update. Set KEYCLOAK_ADMIN_PASSWORD to update it automatically."
fi

echo "Rebuilding frontend with Cloudflare public URLs..."
docker compose build --no-cache frontend

echo "Starting backend and frontend..."
docker compose up -d backend frontend

cat <<EOF
Updated Cloudflare public URLs:
  Frontend: $FRONTEND_URL
  Backend:  $BACKEND_URL
  Keycloak: $KEYCLOAK_URL

Keycloak:
  $KEYCLOAK_STATUS

Docker:
  Rebuilt frontend image and restarted backend/frontend.

Open or hard refresh:
  $FRONTEND_URL
EOF
