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

source "$ROOT_DIR/scripts/runtime-env.sh"
runtime_urls

KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
CONTAINER="${KEYCLOAK_CONTAINER:-}"
FRONTEND_PUBLIC_ORIGINS="${FRONTEND_PUBLIC_ORIGINS:-https://owl-drive.com,https://www.owl-drive.com}"
export FRONTEND_PUBLIC_ORIGINS
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [ -z "$CONTAINER" ]; then
  CONTAINER="$(docker compose ps -q keycloak 2>/dev/null || true)"
fi

if [ -z "$CONTAINER" ]; then
  CONTAINER="owl-keycloak-1"
fi

echo "Configuring Keycloak client $KEYCLOAK_CLIENT_ID for $FRONTEND_LAN_ORIGIN ..."

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

docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh update "realms/$KEYCLOAK_REALM" \
  -s 'sslRequired=none' \
  -s 'registrationAllowed=false'

CLIENT_UUID="$(docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh get clients \
  -r "$KEYCLOAK_REALM" \
  -q "clientId=$KEYCLOAK_CLIENT_ID" \
  --fields id \
  --format csv \
  --noquotes | tail -n 1)"

if [ -z "$CLIENT_UUID" ]; then
  echo "Unable to find Keycloak client: $KEYCLOAK_CLIENT_ID" >&2
  exit 1
fi

docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh get "clients/$CLIENT_UUID" \
  -r "$KEYCLOAK_REALM" > "$TMP_DIR/client.json"

python3 - "$TMP_DIR/client.json" "$TMP_DIR/client-updated.json" <<'PY'
import json
import os
import sys

client_path, updated_path = sys.argv[1:3]
with open(client_path, encoding="utf-8") as fh:
    client = json.load(fh)

frontend_local = os.environ["FRONTEND_LOCAL_ORIGIN"]
frontend_loopback = os.environ["FRONTEND_LOOPBACK_ORIGIN"]
frontend_lan = os.environ["FRONTEND_LAN_ORIGIN"]
frontend_public_origins = [
    origin.strip().rstrip("/")
    for origin in os.environ.get("FRONTEND_PUBLIC_ORIGINS", "").split(",")
    if origin.strip()
]

def merge_list(existing, additions):
    merged = []
    for item in [*(existing or []), *additions]:
        if item and item not in merged:
            merged.append(item)
    return merged

redirect_uris = merge_list(client.get("redirectUris"), [
    frontend_local,
    f"{frontend_local}/*",
    frontend_loopback,
    f"{frontend_loopback}/*",
    frontend_lan,
    f"{frontend_lan}/*",
] + [uri for origin in frontend_public_origins for uri in (origin, f"{origin}/*")])
web_origins = merge_list(client.get("webOrigins"), [
    frontend_local,
    frontend_loopback,
    frontend_lan,
] + frontend_public_origins)

attributes = dict(client.get("attributes") or {})
existing_logout = attributes.get("post.logout.redirect.uris", "")
logout_uris = merge_list(existing_logout.split("##"), [
    f"{frontend_local}/*",
    f"{frontend_loopback}/*",
    f"{frontend_lan}/*",
    *[f"{origin}/*" for origin in frontend_public_origins],
])
attributes["pkce.code.challenge.method"] = "S256"
attributes["post.logout.redirect.uris"] = "##".join(logout_uris)

client["redirectUris"] = redirect_uris
client["webOrigins"] = web_origins
client["attributes"] = attributes

with open(updated_path, "w", encoding="utf-8") as fh:
    json.dump(client, fh, separators=(",", ":"))
PY

docker cp "$TMP_DIR/client-updated.json" "$CONTAINER:/tmp/owl-keycloak-client-updated.json"
docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh update "clients/$CLIENT_UUID" \
  -r "$KEYCLOAK_REALM" \
  -f /tmp/owl-keycloak-client-updated.json

echo "Keycloak client ready for $FRONTEND_LAN_ORIGIN"
