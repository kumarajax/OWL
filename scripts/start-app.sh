#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
LOG_DIR="$ROOT_DIR/logs"
source "$ROOT_DIR/scripts/runtime-env.sh"
runtime_urls

mkdir -p "$RUN_DIR" "$LOG_DIR"

cat >"$ROOT_DIR/frontend/.env.local" <<EOF
NEXT_PUBLIC_KEYCLOAK_REALM=$KEYCLOAK_REALM
NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=$KEYCLOAK_CLIENT_ID
EOF

export KEYCLOAK_INTERNAL_URL
export APP_CORS_ALLOWED_ORIGINS="$FRONTEND_LOCAL_ORIGIN,$FRONTEND_LOOPBACK_ORIGIN,$FRONTEND_LAN_ORIGIN"
export APP_CORS_ALLOWED_ORIGIN_PATTERNS="${APP_CORS_ALLOWED_ORIGIN_PATTERNS:-http://localhost:${FRONTEND_PORT},http://127.0.0.1:${FRONTEND_PORT},http://192.168.*.*:${FRONTEND_PORT},http://10.*.*.*:${FRONTEND_PORT},http://172.*.*.*:${FRONTEND_PORT}}"
export APP_SECURITY_OAUTH2_JWT_ALLOWED_ISSUERS="$KEYCLOAK_LOCAL_ISSUER,$KEYCLOAK_LOOPBACK_ISSUER,$KEYCLOAK_LAN_ISSUER"
export APP_SECURITY_OAUTH2_JWT_ALLOWED_ISSUER_PATTERNS="${APP_SECURITY_OAUTH2_JWT_ALLOWED_ISSUER_PATTERNS:-http://localhost:${KEYCLOAK_PORT}/realms/${KEYCLOAK_REALM},http://127.0.0.1:${KEYCLOAK_PORT}/realms/${KEYCLOAK_REALM},http://192.168.*.*:${KEYCLOAK_PORT}/realms/${KEYCLOAK_REALM},http://10.*.*.*:${KEYCLOAK_PORT}/realms/${KEYCLOAK_REALM},http://172.*.*.*:${KEYCLOAK_PORT}/realms/${KEYCLOAK_REALM}}"
export APP_SECURITY_OAUTH2_JWT_JWK_SET_URI="${APP_SECURITY_OAUTH2_JWT_JWK_SET_URI:-${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs}"
export OWL_NEXT_ALLOWED_DEV_ORIGINS="${OWL_NEXT_ALLOWED_DEV_ORIGINS:-127.0.0.1,*.local,192.168.*.*,10.*.*.*,172.*.*.*,$OWL_HOST_IP_DETECTED}"

if [ -f "$RUN_DIR/backend.pid" ] && kill -0 "$(cat "$RUN_DIR/backend.pid")" 2>/dev/null; then
  echo "Backend already running with PID $(cat "$RUN_DIR/backend.pid")"
else
  echo "Starting backend on http://localhost:8081 ..."
  nohup bash -c 'cd "$1" && exec mvn spring-boot:run' _ "$ROOT_DIR/backend" >"$LOG_DIR/backend.log" 2>&1 &
  echo $! > "$RUN_DIR/backend.pid"
fi

if [ -f "$RUN_DIR/frontend.pid" ] && kill -0 "$(cat "$RUN_DIR/frontend.pid")" 2>/dev/null; then
  echo "Frontend already running with PID $(cat "$RUN_DIR/frontend.pid")"
else
  echo "Starting frontend on http://localhost:3000 ..."
  nohup bash -c '
    cd "$1" || exit 1
    if [ ! -f ".env.local" ]; then
      cp .env.example .env.local
    fi
    exec npm run dev
  ' _ "$ROOT_DIR/frontend" >"$LOG_DIR/frontend.log" 2>&1 &
  echo $! > "$RUN_DIR/frontend.pid"
fi

echo
echo "Started OWL Drive app processes."
echo "Backend log:  $LOG_DIR/backend.log"
echo "Frontend log: $LOG_DIR/frontend.log"
echo
echo "Open: $FRONTEND_LAN_ORIGIN"
echo "Local: $FRONTEND_LOCAL_ORIGIN"
echo "Stop: $ROOT_DIR/scripts/stop-app.sh"
