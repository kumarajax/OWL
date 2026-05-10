#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG_FILE="$ROOT_DIR/infra/minio/extra-drives.conf"
OUTPUT_FILE="$ROOT_DIR/docker-compose.override.yml"

if [ ! -f "$CONFIG_FILE" ]; then
  rm -f "$OUTPUT_FILE"
  exit 0
fi

DRIVE_PATHS=()
while IFS= read -r line; do
  line="${line%$'\r'}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  if [ -z "$line" ] || [ "${line#\#}" != "$line" ]; then
    continue
  fi
  DRIVE_PATHS+=("$line")
done < "$CONFIG_FILE"

if [ "${#DRIVE_PATHS[@]}" -eq 0 ]; then
  rm -f "$OUTPUT_FILE"
  exit 0
fi

python3 - "$ROOT_DIR" "$OUTPUT_FILE" "${DRIVE_PATHS[@]}" <<'PY'
import pathlib
import sys

root_dir = pathlib.Path(sys.argv[1])
output_file = pathlib.Path(sys.argv[2])
raw_paths = sys.argv[3:]

def quote(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')

resolved = []
for raw in raw_paths:
    path = pathlib.Path(raw).expanduser()
    if not path.is_absolute():
        path = (root_dir / path).resolve()
    else:
        path = path.resolve()
    if not path.exists():
        raise SystemExit(f"Configured MinIO drive path does not exist: {path}")
    if not path.is_dir():
        raise SystemExit(f"Configured MinIO drive path is not a directory: {path}")
    resolved.append(str(path))

container_paths = ["/data"] + [f"/minio-drives/disk-{index}" for index in range(1, len(resolved) + 1)]

lines = [
    "services:",
    "  minio:",
    "    command:",
    '      ["server"',
]

for container_path in container_paths:
    lines[-1] += f', "{quote(container_path)}"'

lines[-1] += ', "--console-address", ":9001"]'
lines.extend([
    "    volumes:",
])

for index, path in enumerate(resolved, start=1):
    lines.append(f'      - "{quote(path)}:/minio-drives/disk-{index}"')

lines.append("")
output_file.write_text("\n".join(lines), encoding="utf-8")
PY
