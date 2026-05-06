#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
docker compose up -d --remove-orphans postgres keycloak minio
"$ROOT_DIR/scripts/configure-keycloak-client.sh"
