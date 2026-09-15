#!/bin/sh
set -eu
connect="${CONNECT_URL:-http://connect:8083}"
deadline=$(( $(date +%s) + 180 ))
until curl -sf "$connect/"; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "connect REST not up" >&2
    exit 1
  fi
  sleep 2
done
code=$(curl -s -o /tmp/register-out.txt -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  --data-binary @/connector.json "$connect/connectors" || true)
if [ "$code" != "201" ] && [ "$code" != "409" ]; then
  echo "register failed http=$code" >&2
  cat /tmp/register-out.txt >&2
  exit 1
fi
deadline=$(( $(date +%s) + 120 ))
while :; do
  status=$(curl -sf "$connect/connectors/selection-outbox/status" || true)
  echo "$status"
  if echo "$status" | grep -q '"state":"FAILED"'; then
    echo "connector failed" >&2
    exit 1
  fi
  running=$(echo "$status" | grep -o '"state":"RUNNING"' | wc -l | tr -d ' ')
  if [ "$running" -ge 2 ]; then
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "connector not running" >&2
    exit 1
  fi
  sleep 2
done
