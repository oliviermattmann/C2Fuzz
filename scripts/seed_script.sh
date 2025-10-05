#!/usr/bin/env bash
set -euo pipefail

# ---- Config ----
SRC_ROOT="${SRC_ROOT:-jtreg_seeds_26/compiler}"
BUILD_DIR="${BUILD_DIR:-build_flat}"
OUT_DIR="$BUILD_DIR/out"
TMP_DIR="$BUILD_DIR/tmp"
SEEDS_DIR="${SEEDS_DIR:-seeds}"
LOG="$BUILD_DIR/flat_results.csv"
TIME_LIMIT="${TIME_LIMIT:-10s}"
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
ALLOW_SUN_IMPORTS="${ALLOW_SUN_IMPORTS:-false}"   # set to "true" to allow sun.* imports

mkdir -p "$OUT_DIR" "$TMP_DIR" "$SEEDS_DIR" "$(dirname "$LOG")"
echo "source_path,main_class,imports_ok,compiled,ran_within_${TIME_LIMIT},exit_code,status" > "$LOG"

has_timeout() { command -v timeout >/dev/null 2>&1; }

# Return 0 if file has a main method
has_main() {
  # Strip block and line comments, join to one line, squeeze whitespace.
  local flat
  flat="$(sed -E 's:/\*([^*]|\*+[^*/])*\*/: :g; s://.*::g' "$1" \
          | tr '\n' ' ' \
          | tr -s '[:space:]' ' ')"

  # Look for "... static ... void main ( ... )" with any modifiers/annotations interleaved.
  echo "$flat" | grep -E -qi '\b(static[[:space:]]+)?(public|protected|private|final|synchronized|strictfp|abstract|native|transient|volatile|sealed|non-sealed|@[^ ]+[[:space:]]+)*void[[:space:]]+main[[:space:]]*\(' \
    && echo "$flat" | grep -E -qi '\bstatic\b[[:space:]]*[^;{)]*void[[:space:]]+main[[:space:]]*\('
}

# Extract public top-level class name (basic but works for tests)
extract_public_class() {
  local f="$1"
  # class | enum | interface as "main type"; most jtreg tests use class
  sed -nE 's/.*public[[:space:]]+(class|enum|interface)[[:space:]]+([A-Za-z_][A-Za-z0-9_]*).*/\2/p' "$f" | head -n1
}

# Ensure only JDK imports
imports_ok() {
  local f="$1"
  local pat='^(java|javax|jdk)\.'
  if [[ "${ALLOW_SUN_IMPORTS}" == "true" ]]; then
    pat='^(java|javax|jdk|sun)\.'
  fi
  # collect all non-static import targets
  local bad
  bad="$(grep -E '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?[^;]+;' "$f" \
        | sed -E 's/^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?([^\*;]+)(\.\*)?;.*/\2/' \
        | grep -Ev "$pat" || true)"
  [[ -z "$bad" ]]
}

# Strip a single leading package declaration into $dst
strip_package() {
  local src="$1" dst="$2"
  # remove first "package ...;" if present
  awk '
    BEGIN{removed=0}
    {
      if (!removed && $0 ~ /^[[:space:]]*package[[:space:]]+[A-Za-z0-9_\.]+[[:space:]]*;/) {
        removed=1; next
      }
      print
    }
  ' "$src" > "$dst"
}

# Unique filename helper (keeps extension)
unique_copy() {
  local src="$1" base dst n=0
  base="$(basename "$src")"
  dst="$SEEDS_DIR/$base"
  while [[ -e "$dst" ]]; do
    ((n++))
    dst="$SEEDS_DIR/${base%.java}_$n.java"
  done
  cp "$src" "$dst"
  echo "$dst"
}

# Clean a build (classes) between candidates to avoid bleed-through
clean_out() {
  rm -rf "$OUT_DIR"
  mkdir -p "$OUT_DIR"
}

# ---- Main loop ----
mapfile -t CANDIDATES < <(find "$SRC_ROOT" -type f -name '*.java' | sort)

for SRC in "${CANDIDATES[@]}"; do
  STATUS=""
  IMPORTS_OK="no"
  COMPILE_OK="no"
  RAN_OK="no"
  EXIT_CODE=""

  # Quick filters
  if ! has_main "$SRC"; then
    STATUS="skip-no-main"
    echo "$SRC,,,$COMPILE_OK,,,$STATUS" >> "$LOG"
    continue
  fi
  if ! imports_ok "$SRC"; then
    STATUS="skip-nonjdk-imports"
    echo "$SRC,,no,$COMPILE_OK,,,$STATUS" >> "$LOG"
    continue
  fi

  # Prepare temp: strip package to make file relocatable
  clean_out
  BASENAME="$(basename "$SRC")"
  TMP_SRC="$TMP_DIR/$BASENAME"
  strip_package "$SRC" "$TMP_SRC"

  MAIN_CLASS="$(extract_public_class "$TMP_SRC")"
  if [[ -z "$MAIN_CLASS" ]]; then
    STATUS="skip-no-public-type"
    echo "$SRC,,yes,$COMPILE_OK,,,$STATUS" >> "$LOG"
    continue
  fi

  # Compile *only this file*
  ERRFILE="$TMP_DIR/err.txt"
    set +e
    "$JAVAC" -d "$OUT_DIR" "$TMP_SRC" 1>/dev/null 2>"$ERRFILE"
    COMP_RC=$?
    set -e
    FIRST_ERR="$(head -n 1 "$ERRFILE" 2>/dev/null || true)"
    rm -f "$ERRFILE"

    if [[ $COMP_RC -ne 0 ]]; then
    STATUS="compile-failed"
    echo "$SRC,$MAIN_CLASS,$IMPORTS_OK,no,,,\"${FIRST_ERR//,/; }\"" >> "$LOG"
    continue
    fi

  COMPILE_OK="yes"

  # Run with timeout
  if has_timeout; then
    set +e
    timeout "$TIME_LIMIT" "$JAVA" -cp "$OUT_DIR" "$MAIN_CLASS" >/dev/null 2>&1
    RUN_RC=$?
    set -e
    if [[ $RUN_RC -eq 124 ]]; then
      STATUS="timeout"
      echo "$SRC,$MAIN_CLASS,yes,$COMPILE_OK,no,124,$STATUS" >> "$LOG"
      continue
    fi
  else
    set +e
    "$JAVA" -cp "$OUT_DIR" "$MAIN_CLASS" >/dev/null 2>&1
    RUN_RC=$?
    set -e
  fi

  EXIT_CODE="$RUN_RC"
  if [[ $RUN_RC -eq 0 ]]; then
    RAN_OK="yes"
    STATUS="ok"
    # Keep relocatable source (package stripped)
    DEST="$(unique_copy "$TMP_SRC")"
  else
    STATUS="runtime-error"
  fi

  echo "$SRC,$MAIN_CLASS,yes,$COMPILE_OK,$RAN_OK,$EXIT_CODE,$STATUS" >> "$LOG"
done

echo
echo "DONE."
echo "  Log:    $LOG"
echo "  Seeds:  $SEEDS_DIR  (flat, package-free single-file tests)"
