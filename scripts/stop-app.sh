#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"

stop_pid_file() {
  local name="$1"
  local file="$RUN_DIR/$name.pid"

  if [ ! -f "$file" ]; then
    echo "$name PID file not found."
    return
  fi

  local pid
  pid="$(cat "$file")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "Stopping $name launcher PID $pid ..."
    kill "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
  else
    echo "$name launcher PID $pid is not running."
  fi
  rm -f "$file"
}

stop_port_listener() {
  local label="$1"
  local port="$2"
  local pids

  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [ -z "$pids" ]; then
    echo "No $label listener on port $port."
    return
  fi

  echo "Stopping $label listener(s) on port $port: $pids"
  kill $pids 2>/dev/null || true
  sleep 1

  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [ -n "$pids" ]; then
    echo "Force stopping $label listener(s) on port $port: $pids"
    for pid in $pids; do
      /bin/kill -9 "$pid" 2>/dev/null || true
    done
  fi
}

stop_pid_file backend
stop_pid_file frontend

stop_port_listener backend 8081
stop_port_listener frontend 3000

echo "OWL Drive app processes stopped."
