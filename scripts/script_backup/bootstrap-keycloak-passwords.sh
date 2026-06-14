#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT_DIR/.env"
  set +a
fi

# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/runtime-env.sh"
runtime_urls

KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-}"
KEYCLOAK_SEEDED_USERS="${KEYCLOAK_SEEDED_USERS:-testuser,adminuser}"
KEYCLOAK_SEEDED_PASSWORD="${KEYCLOAK_SEEDED_PASSWORD:-}"

if [ -z "$KEYCLOAK_ADMIN_PASSWORD" ]; then
  echo "Missing KEYCLOAK_ADMIN_PASSWORD in .env" >&2
  exit 1
fi

if [ -z "$KEYCLOAK_SEEDED_PASSWORD" ]; then
  echo "Missing KEYCLOAK_SEEDED_PASSWORD in .env" >&2
  exit 1
fi

CONTAINER="$(docker compose ps -q keycloak 2>/dev/null || true)"
if [ -z "$CONTAINER" ]; then
  CONTAINER="owl-keycloak-1"
fi

echo "Waiting for Keycloak and applying seeded passwords..."

for attempt in $(seq 1 60); do
  if docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
    --server "$KEYCLOAK_INTERNAL_URL" \
    --realm master \
    --user "$KEYCLOAK_ADMIN_USER" \
    --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1; then
    break
  fi

  if [ "$attempt" -eq 60 ]; then
    echo "Keycloak did not become ready in time." >&2
    exit 1
  fi

  sleep 2
done

IFS=',' read -r -a USERNAMES <<< "$KEYCLOAK_SEEDED_USERS"
for raw_username in "${USERNAMES[@]}"; do
  username="$(printf '%s' "$raw_username" | xargs)"
  if [ -z "$username" ]; then
    continue
  fi

  docker exec \
    -e KEYCLOAK_REALM="$KEYCLOAK_REALM" \
    -e TARGET_USERNAME="$username" \
    -e TARGET_PASSWORD="$KEYCLOAK_SEEDED_PASSWORD" \
    "$CONTAINER" \
    /bin/sh -lc '
      set -e
      USER_ID=$(/opt/keycloak/bin/kcadm.sh get users -r "$KEYCLOAK_REALM" -q username="$TARGET_USERNAME" --fields id --format csv --noquotes | tail -n 1)
      if [ -z "$USER_ID" ]; then
        echo "Unable to find seeded Keycloak user: $TARGET_USERNAME" >&2
        exit 1
      fi
      /opt/keycloak/bin/kcadm.sh set-password -r "$KEYCLOAK_REALM" --userid "$USER_ID" --new-password "$TARGET_PASSWORD" --temporary=false
    '
  echo "Updated password for seeded Keycloak user: $username"
done

echo "Seeded Keycloak passwords are synchronized."
