#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
OVERRIDE_FILE="$ROOT_DIR/docker-compose.override.yml"
OVERRIDE_EXAMPLE_FILE="$ROOT_DIR/docker-compose.override.example.yml"

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/sync-storage-config.sh

Purpose:
  - Read DATA_STORAGE_ROOT, DATA_STORAGE_ROOT_2, DATA_STORAGE_ROOT_3, ...
    from .env
  - Regenerate docker-compose.yml, docker-compose.override.yml, and
    docker-compose.override.example.yml so service count and host mounts stay
    aligned with the current drive layout

Notes:
  - Remove a DATA_STORAGE_ROOT_N entry to remove the matching shard/pool.
  - Add a DATA_STORAGE_ROOT_N entry to add the matching shard/pool.
  - This script creates the host-side storage directories for every configured
    shard/pool mount.
USAGE
}

require_file() {
  local path="$1"
  if [ ! -f "$path" ]; then
    echo "Missing file: $path" >&2
    exit 1
  fi
}

load_env_value() {
  local key="$1"
  awk -F= -v key="$key" '
    $1 == key {
      sub(/^[[:space:]]+/, "", $2)
      sub(/[[:space:]]+$/, "", $2)
      print $2
      exit
    }
  ' "$ENV_FILE"
}

root_env_var_name() {
  local index="$1"
  if [ "$index" -eq 1 ]; then
    printf 'DATA_STORAGE_ROOT'
  else
    printf 'DATA_STORAGE_ROOT_%s' "$index"
  fi
}

collect_root_count() {
  local count=0
  while :; do
    local index=$((count + 1))
    local key
    key="$(root_env_var_name "$index")"
    local value
    value="$(load_env_value "$key")"
    if [ "$index" -eq 1 ]; then
      if [ -z "$value" ]; then
        echo "DATA_STORAGE_ROOT is not set in .env" >&2
        exit 1
      fi
    else
      if [ -z "$value" ]; then
        break
      fi
    fi
    count=$index
  done
  printf '%s' "$count"
}

render_override_file() {
  local target="$1"
  local root_count="$2"
  {
    cat <<'EOF'
services:
  postgres:
    volumes:
      - "${DATA_STORAGE_ROOT}/postgres:/var/lib/postgresql/data"
EOF

    local index=2
    while (( index <= root_count )); do
      local root_var
      root_var="$(root_env_var_name "$index")"
      cat <<EOF

  postgres-shard-$index:
    volumes:
      - "\${$root_var}/postgres-shard-$index:/var/lib/postgresql/data"
EOF
      index=$((index + 1))
    done

    cat <<'EOF'

  minio:
    volumes:
      - "${DATA_STORAGE_ROOT}/minio:/data"
EOF

    index=2
    while (( index <= root_count )); do
      local root_var
      root_var="$(root_env_var_name "$index")"
      cat <<EOF

  minio-$index:
    volumes:
      - "\${$root_var}/minio-$index:/data"
EOF
      index=$((index + 1))
    done
  } >"$target"
}

create_storage_directories() {
  local root_count="$1"
  local index=1
  while (( index <= root_count )); do
    local root_var
    root_var="$(root_env_var_name "$index")"
    local root_value
    root_value="$(load_env_value "$root_var")"
    if [ -z "$root_value" ]; then
      echo "Missing value for $root_var in .env" >&2
      exit 1
    fi

    if [ "$index" -eq 1 ]; then
      mkdir -p "$root_value/postgres" "$root_value/minio"
    else
      mkdir -p "$root_value/postgres-shard-$index" "$root_value/minio-$index"
    fi

    index=$((index + 1))
  done
}

normalize_compose_placeholders() {
  local target="$1"
  python3 - "$target" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
text = text.replace(r'\${', '${')
path.write_text(text)
PY
}

render_compose_file() {
  local target="$1"
  local root_count="$2"
  {
    cat <<'EOF'
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: \${POSTGRES_DB:-owldrive}
      POSTGRES_USER: \${POSTGRES_USER:-owldrive}
      POSTGRES_PASSWORD: \${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
EOF

    local index=2
    while (( index <= root_count )); do
      local host_port=$((5431 + index))
      cat <<EOF

  postgres-shard-$index:
    image: postgres:16
    environment:
      POSTGRES_DB: \${POSTGRES_DB:-owldrive}
      POSTGRES_USER: \${POSTGRES_USER:-owldrive}
      POSTGRES_PASSWORD: \${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}
    ports:
      - "$host_port:5432"
    volumes:
      - postgres-shard-$index-data:/var/lib/postgresql/data
EOF
      index=$((index + 1))
    done

    cat <<'EOF'

  keycloak-db-init:
    image: postgres:16
    depends_on:
      - postgres
    environment:
      PGPASSWORD: \${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}
    command:
      [
        "sh",
        "-lc",
        "until pg_isready -h postgres -U ${POSTGRES_USER:-owldrive} -d ${POSTGRES_DB:-owldrive} >/dev/null 2>&1; do sleep 1; done; psql -h postgres -U ${POSTGRES_USER:-owldrive} -d ${POSTGRES_DB:-owldrive} -v ON_ERROR_STOP=1 -c 'CREATE SCHEMA IF NOT EXISTS keycloak AUTHORIZATION ${POSTGRES_USER:-owldrive};'"
      ]
    restart: "no"

  keycloak:
    build:
      context: ./infra/keycloak
    command: ["start-dev", "--import-realm"]
    environment:
      KEYCLOAK_ADMIN: \${KEYCLOAK_ADMIN_USER:-admin}
      KEYCLOAK_ADMIN_PASSWORD: \${KEYCLOAK_ADMIN_PASSWORD:?Set KEYCLOAK_ADMIN_PASSWORD in .env}
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/\${POSTGRES_DB:-owldrive}?currentSchema=keycloak
      KC_DB_USERNAME: \${POSTGRES_USER:-owldrive}
      KC_DB_PASSWORD: \${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}
      KC_FEATURES: persistent-user-sessions
    ports:
      - "8080:8080"
    depends_on:
      keycloak-db-init:
        condition: service_completed_successfully

  minio:
    image: quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: \${MINIO_ROOT_USER:-minioadmin}
      MINIO_ROOT_PASSWORD: \${MINIO_ROOT_PASSWORD:?Set MINIO_ROOT_PASSWORD in .env}
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio-data:/data
EOF

    index=2
    while (( index <= root_count )); do
      local api_port=$((9000 + (index - 1) * 2))
      local console_port=$((api_port + 1))
      cat <<EOF

  minio-$index:
    image: quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: \${MINIO_ROOT_USER:-minioadmin}
      MINIO_ROOT_PASSWORD: \${MINIO_ROOT_PASSWORD:?Set MINIO_ROOT_PASSWORD in .env}
    ports:
      - "$api_port:9000"
      - "$console_port:9001"
    volumes:
      - minio-$index-data:/data
EOF
      index=$((index + 1))
    done

    cat <<'EOF'

  backend:
    build:
      context: ./backend
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/owldrive?currentSchema=app
      SPRING_DATASOURCE_USERNAME: \${POSTGRES_USER:-owldrive}
      SPRING_DATASOURCE_PASSWORD: \${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}
      KEYCLOAK_INTERNAL_URL: http://keycloak:8080
      APP_STORAGE_MINIO_ENDPOINT: http://minio:9000
      APP_STORAGE_MINIO_ACCESS_KEY: \${MINIO_ROOT_USER:-minioadmin}
      APP_STORAGE_MINIO_SECRET_KEY: \${MINIO_ROOT_PASSWORD:?Set MINIO_ROOT_PASSWORD in .env}
      APP_STORAGE_MINIO_BUCKET: owl-drive
EOF

    index=2
    local extra_index=0
    while (( index <= root_count )); do
      cat <<EOF
      APP_STORAGE_POSTGRES_SHARDS_${extra_index}_NAME: shard-$index
      APP_STORAGE_POSTGRES_SHARDS_${extra_index}_JDBC_URL: jdbc:postgresql://postgres-shard-$index:5432/\${POSTGRES_DB:-owldrive}?currentSchema=app
      APP_STORAGE_POSTGRES_SHARDS_${extra_index}_USERNAME: \${POSTGRES_USER:-owldrive}
      APP_STORAGE_POSTGRES_SHARDS_${extra_index}_PASSWORD: \${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}
      APP_STORAGE_MINIO_POOLS_${extra_index}_NAME: pool-$index
      APP_STORAGE_MINIO_POOLS_${extra_index}_ENDPOINT: http://minio-$index:9000
      APP_STORAGE_MINIO_POOLS_${extra_index}_ACCESS_KEY: \${MINIO_ROOT_USER:-minioadmin}
      APP_STORAGE_MINIO_POOLS_${extra_index}_SECRET_KEY: \${MINIO_ROOT_PASSWORD:?Set MINIO_ROOT_PASSWORD in .env}
      APP_STORAGE_MINIO_POOLS_${extra_index}_BUCKET: owl-drive
EOF
      index=$((index + 1))
      extra_index=$((extra_index + 1))
    done

    cat <<'EOF'
      APP_CORS_ALLOWED_ORIGINS: http://localhost:3000,http://127.0.0.1:3000
      APP_SECURITY_OAUTH2_JWT_ALLOWED_ISSUERS: http://localhost:8080/realms/owldrive,http://127.0.0.1:8080/realms/owldrive
    ports:
      - "8081:8081"
    depends_on:
      - postgres
EOF

    index=2
    while (( index <= root_count )); do
      printf '      - postgres-shard-%s\n' "$index"
      index=$((index + 1))
    done

    cat <<'EOF'
      - keycloak
      - minio
EOF

    index=2
    while (( index <= root_count )); do
      printf '      - minio-%s\n' "$index"
      index=$((index + 1))
    done

    cat <<'EOF'

  frontend:
    build:
      context: ./frontend
      args:
        NEXT_PUBLIC_API_BASE_URL: http://localhost:8081
        NEXT_PUBLIC_KEYCLOAK_URL: http://localhost:8080
        NEXT_PUBLIC_UPLOAD_CHUNK_SIZE_BYTES: \${NEXT_PUBLIC_UPLOAD_CHUNK_SIZE_BYTES:-52428800}
    environment:
      NEXT_PUBLIC_API_BASE_URL: http://localhost:8081
      NEXT_PUBLIC_KEYCLOAK_URL: http://localhost:8080
      NEXT_PUBLIC_UPLOAD_CHUNK_SIZE_BYTES: \${NEXT_PUBLIC_UPLOAD_CHUNK_SIZE_BYTES:-52428800}
    ports:
      - "3000:3000"
    depends_on:
      - backend
      - keycloak

volumes:
  postgres-data:
EOF

    index=2
    while (( index <= root_count )); do
      printf '  postgres-shard-%s-data:\n' "$index"
      index=$((index + 1))
    done

    cat <<'EOF'
  minio-data:
EOF

    index=2
    while (( index <= root_count )); do
      printf '  minio-%s-data:\n' "$index"
      index=$((index + 1))
    done
  } >"$target"
}

verify_compose_topology() {
  local root_count="$1"
  local compose_file="$ROOT_DIR/docker-compose.yml"
  local index=2
  while (( index <= root_count )); do
    for token in "postgres-shard-$index" "minio-$index" "APP_STORAGE_POSTGRES_SHARDS_$((index - 2))_NAME" "APP_STORAGE_MINIO_POOLS_$((index - 2))_NAME"; do
      if ! grep -q "$token" "$compose_file"; then
        echo "Expected token not found in docker-compose.yml: $token" >&2
        exit 1
      fi
    done
    index=$((index + 1))
  done
}

main() {
  if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    usage
    exit 0
  fi

  require_file "$ENV_FILE"

  local root_count
  root_count="$(collect_root_count)"

  render_compose_file "$COMPOSE_FILE" "$root_count"
  render_override_file "$OVERRIDE_FILE" "$root_count"
  render_override_file "$OVERRIDE_EXAMPLE_FILE" "$root_count"
  normalize_compose_placeholders "$COMPOSE_FILE"
  normalize_compose_placeholders "$OVERRIDE_FILE"
  normalize_compose_placeholders "$OVERRIDE_EXAMPLE_FILE"
  create_storage_directories "$root_count"
  verify_compose_topology "$root_count"

  echo "Synchronized compose topology for $root_count storage root(s)."
  echo "Wrote:"
  echo "  - $COMPOSE_FILE"
  echo "  - $OVERRIDE_FILE"
  echo "  - $OVERRIDE_EXAMPLE_FILE"
}

main "$@"
