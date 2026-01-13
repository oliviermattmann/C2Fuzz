#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
opto_dir="$repo_root/jdk/src/hotspot/share/opto"
allowlist_file="$repo_root/AFL_GCC_ALLOWLIST"

if [ ! -d "$opto_dir" ]; then
  echo "opto directory not found at $opto_dir" >&2
  exit 1
fi

find "$opto_dir" -type f -print0 \
  | grep -zE '\.(c|cc|cpp|cxx)$' \
  | xargs -0 -n1 basename \
  | sort -u \
  > "$allowlist_file"

echo "Wrote $(wc -l < "$allowlist_file") entries to $allowlist_file"
