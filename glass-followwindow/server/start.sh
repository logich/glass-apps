#!/bin/bash
# Start glass-followwindow server.
# Usage: ./start.sh [--port 8089] [--host 0.0.0.0]
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PY="${PYTHON:-python3}"
SERVER="$SCRIPT_DIR/glass_followwindow.py"

# Kill any prior instance so port is free.
pkill -9 -f "glass_followwindow.py" 2>/dev/null || true
sleep 0.2

exec "$PY" "$SERVER" "$@"
