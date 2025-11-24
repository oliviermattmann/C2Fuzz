#!/usr/bin/env bash
set -euo pipefail

JAVAC_HOME="${JAVAC_JAVA_HOME:-/opt/jdk-release/jdk}"
FUZZER_HOME="${FUZZER_JAVA_HOME:-/opt/jdk-debug/jdk}"
JAVAC_HOST="${JAVAC_HOST:-127.0.0.1}"
JAVAC_PORT="${JAVAC_PORT:-7680}"
JAVAC_READY_TIMEOUT="${JAVAC_READY_TIMEOUT:-60}"
JAVAC_THREADS="${JAVAC_THREADS:-}"

echo "[entrypoint] Starting javac server with JAVA_HOME=${JAVAC_HOME}"
JAVAC_SERVER_CMD=(
  "${JAVAC_HOME}/bin/java"
  -cp "javac_server/out"
  com.example.javacserver.JavacServer
  --host "${JAVAC_HOST}"
  --port "${JAVAC_PORT}"
)
if [[ -n "${JAVAC_THREADS}" ]]; then
  JAVAC_SERVER_CMD+=(--threads "${JAVAC_THREADS}")
fi
"${JAVAC_SERVER_CMD[@]}" &
JAVAC_PID=$!

cleanup() {
  if kill -0 "${JAVAC_PID}" 2>/dev/null; then
    echo "[entrypoint] Stopping javac server (pid ${JAVAC_PID})"
    kill "${JAVAC_PID}" 2>/dev/null || true
    wait "${JAVAC_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if ! /wait-for-port.sh "${JAVAC_HOST}" "${JAVAC_PORT}" "${JAVAC_READY_TIMEOUT}"; then
  echo "[entrypoint] javac server failed to start on ${JAVAC_HOST}:${JAVAC_PORT}" >&2
  exit 1
fi
echo "[entrypoint] javac server ready on ${JAVAC_HOST}:${JAVAC_PORT}"

echo "[entrypoint] Launching fuzzer with JAVA_HOME=${FUZZER_HOME}"
exec "${FUZZER_HOME}/bin/java" \
    -jar target/C2Fuzz-1.0-shaded.jar "$@"
