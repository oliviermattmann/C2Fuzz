#!/usr/bin/env bash
set -Eeuo pipefail

# --- config ---
# Default to the locally built JDK 26 fastdebug binaries. Override via env vars
# if you want different bits, e.g. JAVA=/path/to/java ./test.sh
JAVA="${JAVA:-/home/oli/Documents/education/eth/msc-thesis/code/C2Fuzz/jdk/build/linux-x86_64-server-fastdebug/jdk/bin/java}"
JAVAC="${JAVAC:-/home/oli/Documents/education/eth/msc-thesis/code/C2Fuzz/jdk/build/linux-x86_64-server-fastdebug/jdk/bin/javac}"
MAIN_CLASS="c2fuzz000000198754"
# --------------

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

# Compile everything (some reducers will split/merge files).
if ! "$JAVAC" -d "$workdir" ./*.java >/dev/null 2>&1; then
  exit 1
fi

run_mode_exit() {
  # Run with a given VM mode and print the *numeric exit code*.
  # Time-limit runs so a broken reduction doesn't hang forever.
  local mode="$1"
  set +e
  timeout 30s "$JAVA" $mode -cp "$workdir" "$MAIN_CLASS" >/dev/null 2>&1
  local ec=$?
  set -e
  echo "$ec"
}

e_int="$(run_mode_exit "-Xint")"
e_jit="$(run_mode_exit "-Xbatch -XX:-TieredCompilation")"

# If either run timed out or otherwise unusable, treat as invalid (exit 1).
# timeout(1) returns 124 on timeout; 137 can appear if SIGKILL is used.
if [[ "$e_int" -eq 124 || "$e_jit" -eq 124 || "$e_int" -eq 137 || "$e_jit" -eq 137 ]]; then
  exit 1
fi

# Interesting if exit codes differ (e.g., -Xint succeeds but JIT crashes).
if [[ "$e_int" -ne "$e_jit" ]]; then
  exit 0
else
  exit 2
fi
