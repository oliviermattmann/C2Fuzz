#!/usr/bin/env bash
set -euo pipefail

HOST="${1:?host missing}"
PORT="${2:?port missing}"
TIMEOUT="${3:-60}"

for _ in $(seq "${TIMEOUT}"); do
  if bash -c ">/dev/tcp/${HOST}/${PORT}" >/dev/null 2>&1; then
    exit 0
  fi
  sleep 1
done

echo "Timed out waiting for ${HOST}:${PORT}" >&2
exit 1
