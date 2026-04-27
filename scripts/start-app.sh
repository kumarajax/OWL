#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
LOG_DIR="$ROOT_DIR/logs"

mkdir -p "$RUN_DIR" "$LOG_DIR"

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
echo "Open: http://localhost:3000"
echo "Stop: $ROOT_DIR/scripts/stop-app.sh"
