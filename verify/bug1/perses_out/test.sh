#!/usr/bin/env bash
set -Eeuo pipefail

# --- config ---
JAVA="${JAVA:-java}"
JAVAC="${JAVAC:-javac}"
MAIN_CLASS="AAAAAAAAAAAAAAAAAAADRg"
# --------------

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

# Compile everything (some reducers will split/merge files).
if ! "$JAVAC" -d "$workdir" ./*.java >/dev/null 2>&1; then
  exit 1
fi

extract_second_res() {
  # Print the numeric value of the SECOND “[res] <num>” line.
  # If not found, echo nothing.
  awk '
    /^\[res\][[:space:]]+/ { c++; if (c==2) { print $2; exit } }
  '
}

run_mode() {
  local mode="$1"
  # Limit runtime in case a broken reduction hangs.
  # Use `timeout -k` if available on your system; otherwise drop it.
  timeout 30s "$JAVA" $mode -cp "$workdir" "$MAIN_CLASS" 2>/dev/null \
    | extract_second_res
}

r_int="$(run_mode "-Xint" || true)"
r_jit="$(run_mode "-Xbatch -XX:-TieredCompilation" || true)"

# Must have both numbers.
[[ -n "${r_int:-}" && -n "${r_jit:-}" ]] || exit 1

# Interesting if the numbers differ.
if [[ "$r_int" != "$r_jit" ]]; then
  exit 0
else
  exit 2
fi

