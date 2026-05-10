#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG_FILE="$ROOT_DIR/infra/storage-roots.conf"
OUTPUT_FILE="$ROOT_DIR/docker-compose.override.yml"

if [ ! -f "$CONFIG_FILE" ]; then
  rm -f "$OUTPUT_FILE"
  exit 0
fi

ROOT_PATHS=()
while IFS= read -r line; do
  line="${line%$'\r'}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  if [ -z "$line" ] || [ "${line#\#}" != "$line" ]; then
    continue
  fi
  ROOT_PATHS+=("$line")
done < "$CONFIG_FILE"

if [ "${#ROOT_PATHS[@]}" -eq 0 ]; then
  rm -f "$OUTPUT_FILE"
  exit 0
fi

python3 - "$ROOT_DIR" "$OUTPUT_FILE" "${ROOT_PATHS[@]}" <<'PY'
import pathlib
import sys

root_dir = pathlib.Path(sys.argv[1])
output_file = pathlib.Path(sys.argv[2])
raw_paths = sys.argv[3:]

def quote(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')

resolved = []
seen = set()
for raw in raw_paths:
    path = pathlib.Path(raw).expanduser()
    if not path.is_absolute():
        path = (root_dir / path).resolve()
    else:
        path = path.resolve()
    if not path.exists():
        raise SystemExit(f"Configured storage root does not exist: {path}")
    if not path.is_dir():
        raise SystemExit(f"Configured storage root is not a directory: {path}")
    text = str(path)
    if text in seen:
        continue
    seen.add(text)
    resolved.append(text)

lines = [
    "services:",
    "  backend:",
    "    volumes:",
]

for path in resolved:
    lines.append(f'      - "{quote(path)}:{quote(path)}"')

lines.append("")
output_file.write_text("\n".join(lines), encoding="utf-8")
PY
