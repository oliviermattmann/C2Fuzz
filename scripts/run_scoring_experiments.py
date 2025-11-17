#!/usr/bin/env python3
"""
Batch runner for sweeping C2Fuzz scoring modes and mutator policies.

For each scoring mode (taken from ScoringMode.java) and each requested mutator
policy, the script launches the fuzzer, lets it run for the requested duration,
and then relocates the generated session directory (plus logs and optional
top-case snapshots) into a dedicated experiment folder.
"""

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Iterable, List, Optional, Sequence


DEFAULT_POLICIES = ("UNIFORM", "BANDIT")
GRACEFUL_SHUTDOWN_SECONDS = 30


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def parse_scoring_modes(scoring_file: Path) -> List[str]:
    if not scoring_file.is_file():
        raise FileNotFoundError(f"Scoring mode source not found: {scoring_file}")
    text = scoring_file.read_text(encoding="utf-8")
    match = re.search(r"public\s+enum\s+ScoringMode\s*\{([^;]+);", text, re.DOTALL)
    if not match:
        raise RuntimeError(
            f"Unable to parse scoring modes from {scoring_file}; format may have changed."
        )
    block = match.group(1)
    modes: List[str] = []
    for raw in block.split(","):
        cleaned = raw.strip()
        if not cleaned:
            continue
        if "//" in cleaned:
            cleaned = cleaned.split("//", 1)[0].strip()
        if cleaned:
            modes.append(cleaned)
    if not modes:
        raise RuntimeError(f"No scoring modes parsed from {scoring_file}.")
    return modes


def list_session_dirs(base: Path) -> set[Path]:
    if not base.exists():
        return set()
    return {
        path.resolve()
        for path in base.iterdir()
        if path.is_dir() and path.name.startswith("session_")
    }


def launch_fuzzer(
    cmd: Sequence[str],
    duration_seconds: float,
    workdir: Path,
) -> tuple[int, bool, float]:
    """Run the fuzzer command for up to duration_seconds, return (rc, timed_out, elapsed)."""
    start = time.monotonic()
    timed_out = False
    proc = subprocess.Popen(cmd, cwd=workdir)
    try:
        proc.wait(timeout=duration_seconds)
    except subprocess.TimeoutExpired:
        timed_out = True
        print("    Duration reached, sending SIGINT...", flush=True)
        proc.send_signal(signal.SIGINT)
        try:
            proc.wait(timeout=GRACEFUL_SHUTDOWN_SECONDS)
        except subprocess.TimeoutExpired:
            print("    Grace period expired, force-killing the fuzzer.", flush=True)
            proc.kill()
            proc.wait()
    except KeyboardInterrupt:
        print("    Interrupted by user; stopping fuzzer...", flush=True)
        proc.send_signal(signal.SIGINT)
        proc.wait()
        raise
    elapsed = time.monotonic() - start
    return proc.returncode or 0, timed_out, elapsed


def derive_timestamp(session_dir: Path) -> str:
    name = session_dir.name
    if "_" not in name:
        raise ValueError(f"Unexpected session directory name: {name}")
    return name.split("_", 1)[1]


def ensure_output_root(path: Path) -> Path:
    if path.exists():
        if any(path.iterdir()):
            raise RuntimeError(f"Output directory {path} already exists and is not empty.")
    else:
        path.mkdir(parents=True, exist_ok=True)
    return path


def write_manifest(manifest_path: Path, data: dict) -> None:
    manifest_path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def build_command(
    java_bin: str,
    jar: Path,
    seeds: Path,
    debug_jdk: Optional[str],
    scoring_mode: str,
    policy: str,
    extra_args: Sequence[str],
) -> List[str]:
    cmd: List[str] = [
        java_bin,
        "-jar",
        str(jar),
        "--seeds",
        str(seeds),
    ]
    if debug_jdk:
        cmd.extend(["--debug-jdk", debug_jdk])
    cmd.extend(["--scoring", scoring_mode, "--mutator-policy", policy])
    cmd.extend(extra_args)
    return cmd


def normalize_policies(raw: Optional[Iterable[str]]) -> List[str]:
    if raw is None:
        return list(DEFAULT_POLICIES)
    normalized = []
    for policy in raw:
        upper = policy.strip().upper()
        if upper not in DEFAULT_POLICIES:
            raise SystemExit(
                f"Unknown mutator policy '{policy}'. Expected one of {DEFAULT_POLICIES}."
            )
        normalized.append(upper)
    return normalized


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the fuzzer across all scoring modes and mutator policies.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--seeds",
        required=True,
        help="Path to the seed directory passed to the fuzzer.",
    )
    parser.add_argument(
        "--debug-jdk",
        default=os.environ.get("C2FUZZ_DEBUG_JDK"),
        help="Debug JDK bin directory. If omitted, the fuzzer's default resolution applies.",
    )
    parser.add_argument(
        "--jar",
        default="target/C2Fuzz-1.0-shaded.jar",
        help="Built C2Fuzz jar to execute.",
    )
    parser.add_argument(
        "--java",
        default=os.environ.get("JAVA_BIN", "java"),
        help="Java executable used to launch the jar.",
    )
    parser.add_argument(
        "--duration-minutes",
        type=float,
        default=10.0,
        help="How long each run should execute before being interrupted.",
    )
    parser.add_argument(
        "--output-dir",
        help="Destination directory that will hold the renamed session folders.",
    )
    parser.add_argument(
        "--modes",
        nargs="+",
        help="Optional subset of scoring modes to run (defaults to all defined modes).",
    )
    parser.add_argument(
        "--policies",
        nargs="+",
        help="Optional subset of mutator policies to run (defaults to bandit + uniform).",
    )
    parser.add_argument(
        "--fuzzer-args",
        nargs=argparse.REMAINDER,
        default=[],
        help="Extra arguments forwarded verbatim to the fuzzer CLI (must appear last).",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    root = repo_root()
    seeds_path = (Path(args.seeds)).resolve()
    jar_path = (root / args.jar).resolve() if not os.path.isabs(args.jar) else Path(args.jar).resolve()
    scoring_file = root / "src" / "main" / "java" / "fuzzer" / "runtime" / "ScoringMode.java"

    if not seeds_path.is_dir():
        raise SystemExit(f"Seeds directory not found: {seeds_path}")
    if not jar_path.is_file():
        raise SystemExit(f"Jar not found: {jar_path}")

    available_modes = parse_scoring_modes(scoring_file)
    if args.modes:
        requested = [mode.strip().upper() for mode in args.modes]
        unknown = [mode for mode in requested if mode not in available_modes]
        if unknown:
            raise SystemExit(f"Unknown scoring modes requested: {', '.join(unknown)}")
        scoring_modes = requested
    else:
        scoring_modes = available_modes

    mutator_policies = normalize_policies(args.policies)

    combos = [(mode, policy) for mode in scoring_modes for policy in mutator_policies]
    if not combos:
        raise SystemExit("No scoring-mode/mutator-policy combinations to run.")

    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
    default_output = root / "fuzz_sessions" / f"scoring_sweep_{timestamp}"
    output_root = Path(args.output_dir).resolve() if args.output_dir else default_output.resolve()
    ensure_output_root(output_root)

    fuzz_sessions_root = root / "fuzz_sessions"
    fuzz_sessions_root.mkdir(exist_ok=True)
    logs_root = root / "logs"
    logs_root.mkdir(exist_ok=True)

    duration_seconds = max(int(args.duration_minutes * 60), 1)
    extra_args = list(args.fuzzer_args)

    manifest = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "repo_root": str(root),
        "seeds": str(seeds_path),
        "jar": str(jar_path),
        "java": args.java,
        "duration_minutes": args.duration_minutes,
        "mutator_policies": mutator_policies,
        "scoring_modes": scoring_modes,
        "runs": [],
    }
    manifest_path = output_root / "manifest.json"
    write_manifest(manifest_path, manifest)

    total = len(combos)

    for index, (mode, policy) in enumerate(combos, start=1):
        label = f"{mode.lower()}__{policy.lower()}"
        print(f"[{index}/{total}] Running scoring={mode}, mutator={policy} for {args.duration_minutes} min")
        cmd = build_command(
            args.java,
            jar_path,
            seeds_path,
            args.debug_jdk,
            mode,
            policy,
            extra_args,
        )
        print(f"    Command: {' '.join(cmd)}")
        before = list_session_dirs(fuzz_sessions_root)
        rc, timed_out, elapsed = launch_fuzzer(cmd, duration_seconds, root)
        after = list_session_dirs(fuzz_sessions_root)
        new_sessions = after - before
        if len(new_sessions) != 1:
            raise RuntimeError(
                f"Expected exactly one new session directory, found {len(new_sessions)}."
            )
        session_dir = new_sessions.pop()
        session_ts = derive_timestamp(session_dir)
        dest_dir = output_root / f"{index:02d}_{label}"
        if dest_dir.exists():
            raise RuntimeError(f"Destination {dest_dir} already exists.")
        shutil.move(str(session_dir), str(dest_dir))

        log_file = logs_root / f"fuzzer{session_ts}.log"
        if log_file.exists():
            shutil.copy2(log_file, dest_dir / log_file.name)
        else:
            print(f"    Warning: log file {log_file} not found.", flush=True)

        best_cases = fuzz_sessions_root / f"best_cases_{session_ts}"
        if best_cases.exists():
            shutil.move(str(best_cases), str(dest_dir / best_cases.name))

        run_meta = {
            "label": label,
            "scoring_mode": mode,
            "mutator_policy": policy,
            "session_timestamp": session_ts,
            "return_code": rc,
            "timed_out": timed_out,
            "elapsed_seconds": round(elapsed, 2),
            "command": cmd,
            "dest_dir": str(dest_dir),
        }
        manifest["runs"].append(run_meta)
        write_manifest(manifest_path, manifest)
        print(f"    Session archived under {dest_dir}")

    print(f"Done. Collected {total} runs in {output_root}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        print("\nAborted by user.")
        sys.exit(130)
