#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT_DIR/scripts/runtime-env.sh"
runtime_urls

KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
CONTAINER="${KEYCLOAK_CONTAINER:-owl-keycloak-1}"

redirect_uris_json=$(printf '["%s","%s/*","%s","%s/*","%s","%s/*"]' \
  "$FRONTEND_LOCAL_ORIGIN" "$FRONTEND_LOCAL_ORIGIN" \
  "$FRONTEND_LOOPBACK_ORIGIN" "$FRONTEND_LOOPBACK_ORIGIN" \
  "$FRONTEND_LAN_ORIGIN" "$FRONTEND_LAN_ORIGIN")
web_origins_json=$(printf '["%s","%s","%s"]' \
  "$FRONTEND_LOCAL_ORIGIN" "$FRONTEND_LOOPBACK_ORIGIN" "$FRONTEND_LAN_ORIGIN")
logout_uris="${FRONTEND_LOCAL_ORIGIN}/*##${FRONTEND_LOOPBACK_ORIGIN}/*##${FRONTEND_LAN_ORIGIN}/*"

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

docker exec "$CONTAINER" /bin/sh -c "
  set -e
  CLIENT_UUID=\$(/opt/keycloak/bin/kcadm.sh get clients -r '$KEYCLOAK_REALM' -q clientId='$KEYCLOAK_CLIENT_ID' --fields id --format csv --noquotes | tail -n 1)
  /opt/keycloak/bin/kcadm.sh update clients/\$CLIENT_UUID -r '$KEYCLOAK_REALM' \
    -s 'redirectUris=$redirect_uris_json' \
    -s 'webOrigins=$web_origins_json' \
    -s 'attributes.\"pkce.code.challenge.method\"=S256' \
    -s 'attributes.\"post.logout.redirect.uris\"=$logout_uris'
"

echo "Keycloak client ready for $FRONTEND_LAN_ORIGIN"
