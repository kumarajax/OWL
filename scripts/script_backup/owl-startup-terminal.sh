#!/usr/bin/env bash
set -u

SCRIPT="/home/ajay/OWL_DRIVE/OWL/scripts/start-owl-on-login.sh"

if command -v gnome-terminal >/dev/null 2>&1; then
  exec gnome-terminal --title="OWL Drive Startup" -- bash -lc "$SCRIPT; echo; read -r -p 'Press Enter to close...' _"
fi

if command -v kgx >/dev/null 2>&1; then
  exec kgx --title="OWL Drive Startup" -- bash -lc "$SCRIPT; echo; read -r -p 'Press Enter to close...' _"
fi

if command -v xterm >/dev/null 2>&1; then
  exec xterm -T "OWL Drive Startup" -e bash -lc "$SCRIPT; echo; read -r -p 'Press Enter to close...' _"
fi

"$SCRIPT"
