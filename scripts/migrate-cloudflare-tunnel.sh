#!/usr/bin/env bash
set -euo pipefail

TUNNEL_ID="${TUNNEL_ID:-9eb20cc1-b047-464b-b48b-732324e8a256}"
DOMAIN="${DOMAIN:-owl-drive.com}"
CREDENTIALS_SOURCE="${CREDENTIALS_SOURCE:-$HOME/.cloudflared/${TUNNEL_ID}.json}"
CLOUDFLARED_BIN="${CLOUDFLARED_BIN:-$(command -v cloudflared || true)}"

if [ -z "$CLOUDFLARED_BIN" ]; then
  echo "cloudflared is not installed or not in PATH." >&2
  echo "Install cloudflared first, then rerun this script." >&2
  exit 1
fi

if [ ! -f "$CREDENTIALS_SOURCE" ]; then
  cat >&2 <<EOF
Missing tunnel credentials file:
  $CREDENTIALS_SOURCE

Copy ${TUNNEL_ID}.json from the old machine to:
  $HOME/.cloudflared/${TUNNEL_ID}.json

Or override the path:
  CREDENTIALS_SOURCE=/path/to/${TUNNEL_ID}.json $0
EOF
  exit 1
fi

if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo"
fi

TMP_CONFIG="$(mktemp)"
TMP_SERVICE="$(mktemp)"
trap 'rm -f "$TMP_CONFIG" "$TMP_SERVICE"' EXIT

cat >"$TMP_CONFIG" <<EOF
tunnel: ${TUNNEL_ID}
credentials-file: /etc/cloudflared/${TUNNEL_ID}.json

ingress:
  - hostname: ${DOMAIN}
    service: http://localhost:3000
  - hostname: api.${DOMAIN}
    service: http://localhost:8081
  - hostname: auth.${DOMAIN}
    service: http://localhost:8080
  - service: http_status:404
EOF

cat >"$TMP_SERVICE" <<EOF
[Unit]
Description=cloudflared
After=network-online.target
Wants=network-online.target

[Service]
TimeoutStartSec=15
Type=notify
ExecStart=${CLOUDFLARED_BIN} --no-autoupdate --config /etc/cloudflared/config.yml tunnel run
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF

echo "Installing Cloudflare tunnel service for ${DOMAIN}..."
$SUDO mkdir -p /etc/cloudflared
$SUDO install -m 600 "$CREDENTIALS_SOURCE" "/etc/cloudflared/${TUNNEL_ID}.json"
$SUDO install -m 600 "$TMP_CONFIG" /etc/cloudflared/config.yml
$SUDO install -m 644 "$TMP_SERVICE" /etc/systemd/system/cloudflared.service
$SUDO systemctl daemon-reload
$SUDO systemctl enable cloudflared
$SUDO systemctl restart cloudflared

echo
echo "Cloudflare tunnel service installed."
echo
echo "Verify with:"
echo "  systemctl status cloudflared --no-pager"
echo
echo "Expected DNS records in Cloudflare for ${DOMAIN}:"
echo "  CNAME @    ${TUNNEL_ID}.cfargotunnel.com  Proxied"
echo "  CNAME api  ${TUNNEL_ID}.cfargotunnel.com  Proxied"
echo "  CNAME auth ${TUNNEL_ID}.cfargotunnel.com  Proxied"
