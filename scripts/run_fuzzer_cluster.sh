#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: run_fuzzer_cluster.sh --seeds <dir> --jdk <path> [options] [-- additional Fuzzer args]

Environment overrides (optional):
  CORES         Comma-separated CPU list passed to taskset (default: unset => no binding)
  EXECUTORS     Number of executor threads (default: 4)
  JAR_PATH      Path to the built fuzzer jar (default: target/C2Fuzz-1.0-shaded.jar)
  JAVA_BIN      Java executable used to launch the jar (default: java)
  USE_TASKSET   Set to 0 to skip taskset even if installed

Explicit options override environment values:
  --seeds <dir>             Required seed directory
  --debug-jdk <path>        Path to debug JDK 'bin' directory
  --jdk <path>              Alias for --debug-jdk
  --jar <path>              Jar to execute
  --cores <mask>            taskset core mask string (e.g. 0-3,8-11). Omit to avoid binding
  --executors <n>           Number of executor threads to request from the fuzzer
  --no-taskset              Do not apply taskset
  --java <path>             Java binary used for 'java -jar'
  -h, --help                Show this help and exit
  --                        Pass all remaining arguments to the fuzzer unchanged
EOF
    exit 1
}

CORES=${CORES:-""}
USE_TASKSET=${USE_TASKSET:-1}
EXECUTORS=${EXECUTORS:-4}
JAR_PATH=${JAR_PATH:-"target/C2Fuzz-1.0-shaded.jar"}
JAVA_BIN=${JAVA_BIN:-"java"}
DEBUG_JDK=${DEBUG_JDK:-""}
SEEDS=${SEEDS:-""}
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --seeds)
            [[ $# -ge 2 ]] || usage
            SEEDS=$2
            shift 2
            ;;
        --debug-jdk)
            [[ $# -ge 2 ]] || usage
            DEBUG_JDK=$2
            shift 2
            ;;
        --jdk)
            [[ $# -ge 2 ]] || usage
            DEBUG_JDK=$2
            shift 2
            ;;
        --jar)
            [[ $# -ge 2 ]] || usage
            JAR_PATH=$2
            shift 2
            ;;
        --cores)
            [[ $# -ge 2 ]] || usage
            CORES=$2
            shift 2
            ;;
        --executors)
            [[ $# -ge 2 ]] || usage
            EXECUTORS=$2
            shift 2
            ;;
        --no-taskset)
            USE_TASKSET=0
            shift
            ;;
        --java)
            [[ $# -ge 2 ]] || usage
            JAVA_BIN=$2
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        --)
            shift
            EXTRA_ARGS+=("$@")
            break
            ;;
        *)
            EXTRA_ARGS+=("$1")
            shift
            ;;
    esac
done

if [[ -z "$SEEDS" ]]; then
    printf 'Error: --seeds <dir> is required.\n' >&2
    usage
fi

if [[ -z "$DEBUG_JDK" ]]; then
    printf 'Error: a debug JDK path is required (use --debug-jdk or --jdk).\n' >&2
    usage
fi

if [[ ! -f "$JAR_PATH" ]]; then
    printf 'Error: jar not found at %s\n' "$JAR_PATH" >&2
    exit 1
fi

if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
    printf 'Error: java executable "%s" not found in PATH.\n' "$JAVA_BIN" >&2
    exit 1
fi

cmd=( "$JAVA_BIN" -jar "$JAR_PATH"
      --seeds "$SEEDS"
      --debug-jdk "$DEBUG_JDK"
      --executors "$EXECUTORS" )

if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
    cmd+=( "${EXTRA_ARGS[@]}" )
fi

if [[ $USE_TASKSET -eq 1 && -n "$CORES" ]]; then
    if command -v taskset >/dev/null 2>&1; then
        exec taskset -c "$CORES" "${cmd[@]}"
    else
        printf 'Warning: taskset not found; running without CPU affinity\n' >&2
        exec "${cmd[@]}"
    fi
else
    exec "${cmd[@]}"
fi
