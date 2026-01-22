#!/usr/bin/env python3
"""
Automates corpus × scheduler × scoring sweeps for C2Fuzz inside Docker.

For every requested combination the script launches the fuzzer container, lets
it run for the configured duration, interrupts it cleanly, and then records the
generated session folder (plus the corresponding log) in a manifest. Host-side
`fuzz_sessions/` and `logs/` are bind-mounted into the container so artefacts
are collected locally.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

from run_config_sweep import (
    DEFAULT_CORPORA,
    DEFAULT_GRACEFUL_SHUTDOWN_SECONDS,
    DEFAULT_POLICIES,
    derive_timestamp,
    ensure_output_root,
    list_session_dirs,
    normalize,
    repo_root,
    write_manifest,
)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run C2Fuzz sweeps inside Docker (corpus × scheduler × scoring).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--seeds", required=True, help="Host seed directory to mount read-only.")
    parser.add_argument(
        "--host-sessions",
        help="Host directory where session folders are written/mounted (defaults to <repo>/fuzz_sessions).",
    )
    parser.add_argument(
        "--host-logs",
        help="Host directory where logs are written/mounted (defaults to <repo>/logs).",
    )
    parser.add_argument(
        "--container-seeds",
        default="/app/seeds",
        help="Path inside the container where seeds are mounted.",
    )
    parser.add_argument(
        "--container-sessions",
        default="/app/fuzz_sessions",
        help="Container path bound to the host fuzz_sessions directory.",
    )
    parser.add_argument(
        "--container-logs",
        default="/app/logs",
        help="Container path bound to the host logs directory.",
    )
    parser.add_argument(
        "--docker-cmd",
        nargs="+",
        default=["docker"],
        help="Docker client command (e.g., `docker` or `sudo docker`).",
    )
    parser.add_argument(
        "--network",
        default="none",
        help="Docker network mode to use for runs (passed to --network).",
    )
    parser.add_argument(
        "--fuzzer-seeds",
        help="Path inside the container passed to the fuzzer as --seeds. Defaults to --container-seeds.",
    )
    parser.add_argument("--docker-image", default="c2fuzz:cov", help="Docker image to run.")
    parser.add_argument(
        "--name-prefix",
        default="c2fuzz_sweep",
        help="Prefix used for container names (timestamp and label are appended).",
    )
    parser.add_argument(
        "--allow-seed-writes",
        action="store_true",
        help="Mount seeds without the :ro flag (defaults to read-only).",
    )
    parser.add_argument(
        "--skip-seed-check",
        action="store_true",
        help="Skip host-side existence check for --seeds (useful when pointing at a remote mount).",
    )
    parser.add_argument(
        "--duration-minutes",
        type=float,
        default=10.0,
        help="Per-run wall-clock duration before the fuzzer is interrupted.",
    )
    parser.add_argument(
        "--grace-seconds",
        type=float,
        default=DEFAULT_GRACEFUL_SHUTDOWN_SECONDS,
        help="How long to wait for a clean shutdown after SIGINT before force-killing the fuzzer.",
    )
    parser.add_argument(
        "--javac-threads",
        type=int,
        help="Number of threads for the javac server (sets JAVAC_THREADS env in the container).",
    )
    parser.add_argument(
        "--cpus",
        type=float,
        help="Limit the container to the given number of CPUs (passed to --cpus).",
    )
    parser.add_argument(
        "--cpuset-cpus",
        help="Pin the container to specific CPU cores (passed to --cpuset-cpus).",
    )
    parser.add_argument(
        "--cpuset-mems",
        help="Pin the container to specific NUMA nodes (passed to --cpuset-mems).",
    )
    parser.add_argument(
        "--output-dir",
        help="Destination directory for archived sessions (defaults to fuzz_sessions/experiment_docker_<ts>).",
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


def volume_arg(host: Path, container: str, readonly: bool) -> str:
    suffix = ":ro" if readonly else ""
    return f"{host}:{container}{suffix}"


def sanitize_name(prefix: str, timestamp: str, label: str) -> str:
    base = f"{prefix}_{timestamp}_{label}".lower()
    return re.sub(r"[^a-z0-9_.-]+", "-", base)


def force_remove_container(name: str, docker_cmd: Sequence[str]) -> None:
    try:
        subprocess.run(
            [*docker_cmd, "rm", "-f", name],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    except Exception:
        # Best-effort cleanup; ignore failures so the sweep can continue.
        pass


def build_docker_command(
    docker_cmd: Sequence[str],
    image: str,
    seeds_host: Path,
    seeds_container: str,
    seeds_arg: str,
    sessions_host: Path,
    sessions_container: str,
    logs_host: Path,
    logs_container: str,
    name_prefix: str,
    timestamp: str,
    label: str,
    network: str,
    scoring_mode: str,
    mutator_policy: str,
    corpus_policy: str,
    rng_seed: Optional[int],
    extra_args: Sequence[str],
    readonly_seeds: bool,
    javac_threads: Optional[int],
    cpus: Optional[float],
    cpuset_cpus: Optional[str],
    cpuset_mems: Optional[str],
) -> Tuple[str, List[str]]:
    container_name = sanitize_name(name_prefix, timestamp, label)
    cmd: List[str] = [
        *docker_cmd,
        "run",
        "--rm",
        "--init",
        "--name",
        container_name,
        "--network",
        network,
        "--sig-proxy=true",
        "--user",
        f"{os.getuid()}:{os.getgid()}",
        "-v",
        volume_arg(seeds_host, seeds_container, readonly_seeds),
        "-v",
        volume_arg(sessions_host, sessions_container, False),
        "-v",
        volume_arg(logs_host, logs_container, False),
    ]
    if javac_threads is not None:
        cmd.extend(["-e", f"JAVAC_THREADS={javac_threads}"])
    if cpus is not None:
        cmd.extend(["--cpus", str(cpus)])
    if cpuset_cpus:
        cmd.extend(["--cpuset-cpus", cpuset_cpus])
    if cpuset_mems:
        cmd.extend(["--cpuset-mems", cpuset_mems])
    cmd += [
        image,
        "--seeds",
        str(seeds_arg),
        "--scoring",
        scoring_mode,
        "--mutator-policy",
        mutator_policy.lower(),
        "--corpus-policy",
        corpus_policy.lower(),
    ]
    if rng_seed is not None:
        cmd.extend(["--rng", str(rng_seed)])
    cmd.extend(extra_args)
    return container_name, cmd


def launch_container(
    cmd: Sequence[str],
    duration_seconds: float,
    workdir: Path,
    grace_seconds: float,
    container_name: str,
    docker_cmd: Sequence[str],
) -> Tuple[int, bool, float]:
    start = time.monotonic()
    timed_out = False
    proc = subprocess.Popen(cmd, cwd=workdir)
    try:
        proc.wait(timeout=duration_seconds)
    except subprocess.TimeoutExpired:
        timed_out = True
        print("    Duration reached, sending SIGINT to container...", flush=True)
        subprocess.run(
            [*docker_cmd, "kill", "--signal=INT", container_name],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        try:
            proc.wait(timeout=grace_seconds)
        except subprocess.TimeoutExpired:
            print("    Grace period expired, force-killing the container.", flush=True)
            timed_out = True
            subprocess.run(
                [*docker_cmd, "kill", "--signal=KILL", container_name],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            proc.wait()
            force_remove_container(container_name, docker_cmd)
    except KeyboardInterrupt:
        print("    Interrupted by user; stopping container...", flush=True)
        subprocess.run(
            [*docker_cmd, "kill", "--signal=INT", container_name],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        proc.wait()
        force_remove_container(container_name, docker_cmd)
        raise
    force_remove_container(container_name, docker_cmd)
    elapsed = time.monotonic() - start
    return proc.returncode or 0, timed_out, elapsed


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    root = repo_root()
    seeds_path = Path(args.seeds).resolve()
    if not args.skip_seed_check and not seeds_path.is_dir():
        raise SystemExit(f"Seeds directory not found: {seeds_path}")

    default_sessions_root = (root / "fuzz_sessions").resolve()
    default_logs_root = (root / "logs").resolve()
    sessions_root = Path(args.host_sessions).resolve() if args.host_sessions else default_sessions_root
    logs_root = Path(args.host_logs).resolve() if args.host_logs else default_logs_root

    # Keep the list of scoring modes local to this script to avoid parsing Java sources.
    default_scoring_modes = [
        "PF_IDF",
        "ABSOLUTE_COUNT",
        "PAIR_COVERAGE",
        "INTERACTION_DIVERSITY",
        "NOVEL_FEATURE_BONUS",
        "INTERACTION_PAIR_WEIGHTED",
        "UNIFORM",
    ]
    if args.modes:
        requested = [mode.strip().upper() for mode in args.modes]
        unknown = [mode for mode in requested if mode not in default_scoring_modes]
        if unknown:
            raise SystemExit(f"Unknown scoring modes requested: {', '.join(unknown)}")
        scoring_modes = requested
    else:
        scoring_modes = default_scoring_modes

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
    default_output = sessions_root / f"experiment_docker_{timestamp}"
    output_root = Path(args.output_dir).resolve() if args.output_dir else default_output
    ensure_output_root(output_root)

    sessions_root.mkdir(parents=True, exist_ok=True)
    logs_root.mkdir(parents=True, exist_ok=True)

    duration_seconds = max(int(args.duration_minutes * 60), 1)
    grace_seconds = max(args.grace_seconds, 1.0)
    runs_per_combo = max(args.runs_per_combo, 1)
    rng_seeds = args.rng_seeds or []
    extra_args = list(args.fuzzer_args)
    readonly_seeds = not args.allow_seed_writes
    fuzzer_seeds = args.fuzzer_seeds or args.container_seeds

    manifest = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "repo_root": str(root),
        "seeds_host": str(seeds_path),
        "container_seeds": str(args.container_seeds),
        "fuzzer_seeds": str(fuzzer_seeds),
        "container_sessions": str(args.container_sessions),
        "container_logs": str(args.container_logs),
        "docker_cmd": list(args.docker_cmd),
        "docker_image": args.docker_image,
        "docker_network": args.network,
        "sessions_host": str(sessions_root),
        "logs_host": str(logs_root),
        "duration_minutes": args.duration_minutes,
        "grace_seconds": grace_seconds,
        "mutator_policies": mutator_policies,
        "corpus_policies": corpus_policies,
        "scoring_modes": scoring_modes,
        "runs_per_combo": runs_per_combo,
        "extra_args": extra_args,
        "javac_threads": args.javac_threads,
        "cpus": args.cpus,
        "cpuset_cpus": args.cpuset_cpus,
        "cpuset_mems": args.cpuset_mems,
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
            container_name, cmd = build_docker_command(
                args.docker_cmd,
                args.docker_image,
                seeds_path,
                args.container_seeds,
                fuzzer_seeds,
                sessions_root,
                args.container_sessions,
                logs_root,
                args.container_logs,
                args.name_prefix,
                timestamp,
                label,
                args.network,
                mode,
                policy,
                corpus,
                rng,
                extra_args,
                readonly_seeds,
                args.javac_threads,
                args.cpus,
                args.cpuset_cpus,
                args.cpuset_mems,
            )
            print(f"    Command: {' '.join(cmd)}")
            before = list_session_dirs(sessions_root)
            rc, timed_out, elapsed = launch_container(
                cmd, duration_seconds, root, grace_seconds, container_name, args.docker_cmd
            )
            after = list_session_dirs(sessions_root)
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
            dest_dir = output_root / f"{session_ts}__{label}"
            print(f"    Moving session to {dest_dir}")
            if dest_dir.exists():
                raise RuntimeError(f"Destination already exists: {dest_dir}")
            shutil.move(str(session_dir), dest_dir)

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
                "source_dir": str(session_dir),
                "dest_dir": str(dest_dir),
                "container_name": container_name,
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
        print("\nAborted by user.")
        sys.exit(130)
