#!/usr/bin/env bash
# Wrapper used by run_config_sweep.py (or manual invocations) to launch the fuzzer inside Docker.
# Each run gets a unique container name, and we force-remove the container on exit/interrupt.
# If TIMEOUT_SECS is set, the docker client is wrapped in `timeout` so the container cannot
# outlive the requested duration even if the parent process is killed.
set -euo pipefail

name="c2fuzz_sweep_$$"
host_sessions_dir="$(pwd)/fuzz_sessions"
host_logs_dir="$(pwd)/logs"
mkdir -p "${host_sessions_dir}" "${host_logs_dir}"
cleanup() {
  sudo docker rm -f "${name}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

run_cmd=(
  sudo docker run --rm --init --name "${name}" --sig-proxy=true
  --user "$(id -u):$(id -g)"
  -v "${host_sessions_dir}:/app/fuzz_sessions"
  -v "${host_logs_dir}:/app/logs"
  c2fuzz:latest
  "$@"
)

if [[ -n "${TIMEOUT_SECS:-}" ]]; then
  sudo timeout --signal=INT --kill-after=10 "${TIMEOUT_SECS}s" "${run_cmd[@]}"
else
  "${run_cmd[@]}"
fi
