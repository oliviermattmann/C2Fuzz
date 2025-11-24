#!/usr/bin/env python3
"""
Automates corpus × scheduler × scoring sweeps for C2Fuzz.

For every requested combination the script launches the fuzzer, lets it run
for the configured duration, interrupts it cleanly, and then relocates the
generated session folder (plus the corresponding log) into a dedicated
results directory. Use bind mounts (or the docker helper script) so the
session directories are accessible from the host.
"""

from __future__ import annotations

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
from typing import Iterable, List, Optional, Sequence, Tuple


DEFAULT_POLICIES = ("UNIFORM", "MOP", "BANDIT")
DEFAULT_CORPORA = ("CHAMPION", "RANDOM")
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
) -> Tuple[int, bool, float]:
    start = time.monotonic()
    timed_out = False
    env = os.environ.copy()
    env["TIMEOUT_SECS"] = str(duration_seconds)
    proc = subprocess.Popen(cmd, cwd=workdir, env=env)
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
    mutator_policy: str,
    corpus_policy: str,
    rng_seed: Optional[int],
    extra_args: Sequence[str],
) -> List[str]:
    cmd: List[str] = [
        java_bin,
        "-jar",
        str(jar),
        "--seeds",
        str(seeds),
        "--scoring",
        scoring_mode,
        "--mutator-policy",
        mutator_policy.lower(),
        "--corpus-policy",
        corpus_policy.lower(),
    ]
    if debug_jdk:
        cmd.extend(["--debug-jdk", debug_jdk])
    if rng_seed is not None:
        cmd.extend(["--rng", str(rng_seed)])
    cmd.extend(extra_args)
    return cmd


def normalize(values: Optional[Iterable[str]], allowed: Iterable[str], label: str) -> List[str]:
    options = [opt.upper() for opt in allowed]
    if values is None:
        return options
    normalized: List[str] = []
    for raw in values:
        upper = raw.strip().upper()
        if upper not in options:
            raise SystemExit(f"Unknown {label} '{raw}'. Expected one of {options}.")
        normalized.append(upper)
    return normalized


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run C2Fuzz over corpus × scheduler × scoring combinations.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--seeds", required=True, help="Seed directory passed to the fuzzer.")
    parser.add_argument(
        "--skip-seed-check",
        action="store_true",
        help="Skip host-side existence check for --seeds (useful when pointing at a container path).",
    )
    parser.add_argument(
        "--debug-jdk",
        default=os.environ.get("C2FUZZ_DEBUG_JDK"),
        help="Debug JDK bin directory; fall back to C2FUZZ_DEBUG_JDK if unset.",
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
        help="Per-run wall-clock duration before the fuzzer is interrupted.",
    )
    parser.add_argument(
        "--output-dir",
        help="Destination directory for archived sessions (defaults to fuzz_sessions/experiment_<ts>).",
    )
    parser.add_argument(
        "--modes",
        nargs="+",
        help="Optional subset of scoring modes (defaults to every value in ScoringMode.java).",
    )
    parser.add_argument(
        "--mutator-policies",
        nargs="+",
        help=f"Subset of mutator schedulers. Defaults to {', '.join(DEFAULT_POLICIES)}.",
    )
    parser.add_argument(
        "--corpus-policies",
        nargs="+",
        help=f"Subset of corpus managers. Defaults to {', '.join(DEFAULT_CORPORA)}.",
    )
    parser.add_argument(
        "--runs-per-combo",
        type=int,
        default=1,
        help="How many independent runs to execute for each combination.",
    )
    parser.add_argument(
        "--rng-seeds",
        nargs="+",
        type=int,
        help="Optional RNG seeds applied per repetition (cycled if fewer than runs-per-combo).",
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
    seeds_path = Path(args.seeds)
    if not args.skip_seed_check:
        seeds_path = seeds_path.resolve()
    jar_path = (
        (root / args.jar).resolve() if not os.path.isabs(args.jar) else Path(args.jar).resolve()
    )
    scoring_file = root / "src" / "main" / "java" / "fuzzer" / "runtime" / "ScoringMode.java"

    if not args.skip_seed_check and not seeds_path.is_dir():
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

    mutator_policies = normalize(args.mutator_policies, DEFAULT_POLICIES, "mutator policy")
    corpus_policies = normalize(args.corpus_policies, DEFAULT_CORPORA, "corpus policy")

    combos = [
        (mode, policy, corpus)
        for mode in scoring_modes
        for policy in mutator_policies
        for corpus in corpus_policies
    ]
    if not combos:
        raise SystemExit("No configurations to run.")

    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
    default_output = root / "fuzz_sessions" / f"experiment_grid_{timestamp}"
    output_root = Path(args.output_dir).resolve() if args.output_dir else default_output
    ensure_output_root(output_root)

    fuzz_sessions_root = root / "fuzz_sessions"
    fuzz_sessions_root.mkdir(exist_ok=True)
    logs_root = root / "logs"
    logs_root.mkdir(exist_ok=True)

    duration_seconds = max(int(args.duration_minutes * 60), 1)
    extra_args = list(args.fuzzer_args)
    runs_per_combo = max(args.runs_per_combo, 1)
    rng_seeds = args.rng_seeds or []

    manifest = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "repo_root": str(root),
        "seeds": str(seeds_path),
        "jar": str(jar_path),
        "java": args.java,
        "duration_minutes": args.duration_minutes,
        "mutator_policies": mutator_policies,
        "corpus_policies": corpus_policies,
        "scoring_modes": scoring_modes,
        "runs_per_combo": runs_per_combo,
        "extra_args": extra_args,
        "runs": [],
    }
    manifest_path = output_root / "manifest.json"
    write_manifest(manifest_path, manifest)

    total_runs = len(combos) * runs_per_combo
    completed = 0

    for mode, policy, corpus in combos:
        for repetition in range(runs_per_combo):
            completed += 1
            rng = None
            if rng_seeds:
                rng = rng_seeds[repetition % len(rng_seeds)]
            label = f"{mode.lower()}__{policy.lower()}__{corpus.lower()}__run{repetition + 1:02d}"
            print(
                f"[{completed}/{total_runs}] scoring={mode}, mutator={policy}, corpus={corpus}, "
                f"run {repetition + 1}/{runs_per_combo}"
            )
            cmd = build_command(
                args.java,
                jar_path,
                seeds_path,
                args.debug_jdk,
                mode,
                policy,
                corpus,
                rng,
                extra_args,
            )
            print(f"    Command: {' '.join(cmd)}")
            before = list_session_dirs(fuzz_sessions_root)
            rc, timed_out, elapsed = launch_fuzzer(cmd, duration_seconds, root)
            after = list_session_dirs(fuzz_sessions_root)
            new_sessions = list(after - before)
            if not new_sessions:
                raise RuntimeError("No new session directory found after fuzzer run.")
            if len(new_sessions) > 1:
                new_sessions.sort(key=lambda p: p.stat().st_mtime, reverse=True)
                print(
                    f"    Warning: found {len(new_sessions)} new session directories; "
                    f"using newest {new_sessions[0].name} and ignoring the rest."
                )
            session_dir = new_sessions[0]
            session_ts = derive_timestamp(session_dir)
            # Keep session directories in-place to avoid conflicts when running multiple
            # sweeps in parallel; manifest still records the source path.
            dest_dir = session_dir

            run_meta = {
                "label": label,
                "scoring_mode": mode,
                "mutator_policy": policy,
                "corpus_policy": corpus,
                "session_timestamp": session_ts,
                "return_code": rc,
                "timed_out": timed_out,
                "elapsed_seconds": round(elapsed, 2),
                "rng_seed": rng,
                "command": cmd,
                "dest_dir": str(dest_dir),
            }
            manifest["runs"].append(run_meta)
            write_manifest(manifest_path, manifest)
            print(f"    Session archived under {dest_dir}")

    print(f"Done. Collected {total_runs} runs in {output_root}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        print("\\nAborted by user.")
        sys.exit(130)
