#!/usr/bin/env bash
set -euo pipefail
port="${HEALTHBRIDGE_PORT:-8765}"
case "$port" in *[!0-9]*|"") echo "HEALTHBRIDGE_PORT must be numeric." >&2; exit 1;; esac
if [ "$port" -lt 1024 ] || [ "$port" -gt 65535 ]; then echo "Port must be 1024-65535." >&2; exit 1; fi
command -v adb >/dev/null || { echo "Install Android platform-tools first." >&2; exit 1; }
command -v cloudflared >/dev/null || { echo "Install Cloudflare's tunnel client: brew install cloudflared" >&2; exit 1; }
adb get-state >/dev/null
adb forward "tcp:$port" "tcp:$port"
status="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:$port/v1/status")"
if [ "$status" != "401" ]; then
  echo "Start the Android MCP Bridge gateway. Its unauthenticated status endpoint must reject access with HTTP 401." >&2
  exit 1
fi
printf '%s\n' \
  "Starting a public HTTPS tunnel to your authenticated phone gateway." \
  "The Mac, USB forwarding and this process must stay running." \
  "OpenAI and the tunnel provider become part of the data path. Never use a client with more permissions than needed." \
  "Use the printed HTTPS URL in chatgpt-actions.yaml. Stop this command to close internet access."
exec cloudflared tunnel --url "http://127.0.0.1:$port" --http-host-header "127.0.0.1:$port"
