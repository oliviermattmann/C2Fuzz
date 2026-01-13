#!/usr/bin/env python3
"""
Filter runtime/compiler seeds to those that actually emit C2 optimization events.

Each seed is compiled in isolation and then executed with:
  -Xbatch -XX:+UnlockDiagnosticVMOptions -XX:-TieredCompilation
  -XX:+TraceC2Optimizations -XX:+DisplayVMOutputToStderr -XX:-DisplayVMOutputToStdout

The script looks for the structured markers produced by the patched
TraceC2Optimizations flag (OPTS_START/OPTS_END) that the fuzzer parses via
JVMOutputParser. Seeds without any such markers are discarded.

Usage:
  python3 scripts/filter_c2_optimized_seeds.py \
      --seeds seeds/runtime_compiler \
      --java /path/to/debug/jdk/bin/java \
      [--output seeds_with_opts.txt] [--jobs 4]
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Iterable, List, Optional, Tuple, Union


OPT_MARKER = "OPTS_START"


@dataclass
class SeedResult:
    path: Path
    compiled: bool
    ran: bool
    has_opts: bool
    exit_code: Optional[int]
    timed_out: bool
    message: str = ""


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(
        description="Keep only seeds that produce TraceC2Optimizations output."
    )
    ap.add_argument(
        "--seeds",
        default="seeds/runtime_compiler",
        help="Directory containing .java seeds (default: seeds/runtime_compiler).",
    )
    ap.add_argument(
        "--java",
        dest="java_bin",
        help="Path to a debug/slowdebug java binary with TraceC2Optimizations enabled.",
    )
    ap.add_argument(
        "--javac",
        dest="javac_bin",
        help="Path to javac; defaults to the sibling of --java or $JAVA_HOME/bin/javac.",
    )
    ap.add_argument(
        "--timeout",
        type=float,
        default=15.0,
        help="Per-seed runtime timeout in seconds (default: 15).",
    )
    ap.add_argument(
        "--compile-timeout",
        type=float,
        default=45.0,
        help="Per-seed compilation timeout in seconds (default: 45).",
    )
    ap.add_argument(
        "--jobs",
        type=int,
        default=1,
        help="Run this many seeds in parallel (default: 1).",
    )
    ap.add_argument(
        "--output",
        help="Optional file to write the list of seeds that produced optimizations.",
    )
    ap.add_argument(
        "--verbose",
        action="store_true",
        help="Print failures and truncated VM output for troubleshooting.",
    )
    ap.add_argument(
        "--skip-flag-check",
        action="store_true",
        help="Skip the upfront -XX:+TraceC2Optimizations support check.",
    )
    return ap.parse_args()


def first_existing(paths: Iterable[Path]) -> Optional[Path]:
    for p in paths:
        if p and p.exists() and os.access(p, os.X_OK):
            return p
    return None


def discover_java_bin(cli_value: Optional[str]) -> Path:
    if cli_value:
        return Path(cli_value).expanduser()

    env_candidates = []
    for env in ("JAVA_BIN", "DEBUG_JDK_PATH"):
        val = os.environ.get(env)
        if not val:
            continue
        p = Path(val).expanduser()
        env_candidates.append(p if p.name == "java" else p / "java")

    repo_root = Path(__file__).resolve().parents[1]
    default_candidates = [
        repo_root / "jdk/build/linux-x86_64-server-fastdebug/images/jdk/bin/java",
        repo_root / "jdk/build/linux-x86_64-server-fastdebug/jdk/bin/java",
        repo_root / "jdk/build/linux-x86_64-server-slowdebug/images/jdk/bin/java",
        repo_root / "jdk/build/linux-x86_64-server-slowdebug/jdk/bin/java",
    ]

    java_bin = first_existing(env_candidates + default_candidates)
    if not java_bin:
        raise SystemExit("No usable java binary found; pass --java to specify one.")
    return java_bin


def discover_javac_bin(cli_value: Optional[str], java_bin: Path) -> Path:
    if cli_value:
        return Path(cli_value).expanduser()

    sibling = java_bin.with_name("javac")
    if sibling.exists() and os.access(sibling, os.X_OK):
        return sibling

    system_javac = shutil.which("javac")
    if system_javac:
        return Path(system_javac)

    raise SystemExit("No javac found; provide --javac explicitly.")


def check_trace_flag(java_bin: Path) -> None:
    cmd = [
        str(java_bin),
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+TraceC2Optimizations",
        "-version",
    ]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    except subprocess.TimeoutExpired:
        raise SystemExit("TraceC2Optimizations support check timed out.")

    if res.returncode != 0:
        stderr = res.stderr.strip()
        if "Unrecognized VM option" in stderr:
            raise SystemExit(
                "The provided java does not support -XX:+TraceC2Optimizations "
                "(requires a debug/slowdebug build)."
            )
        raise SystemExit(f"java -version failed: {stderr}")


def run_cmd(cmd: List[str], timeout: float) -> Tuple[Optional[int], str, str, bool]:
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return res.returncode, res.stdout, res.stderr, False
    except subprocess.TimeoutExpired as exc:
        return None, _to_text(exc.stdout), _to_text(exc.stderr), True


def _to_text(val: Union[str, bytes, bytearray, None]) -> str:
    if val is None:
        return ""
    if isinstance(val, (bytes, bytearray)):
        try:
            return val.decode("utf-8", errors="replace")
        except Exception:
            return val.decode(errors="replace")
    return str(val)


def process_seed(
    seed: Path,
    java_bin: Path,
    javac_bin: Path,
    compile_timeout: float,
    runtime_timeout: float,
    verbose: bool,
) -> SeedResult:
    with tempfile.TemporaryDirectory() as tmpdir:
        compile_cmd = [str(javac_bin), "-d", tmpdir, str(seed)]
        cret, cstdout, cstderr, ctimeout = run_cmd(compile_cmd, compile_timeout)
        if ctimeout:
            return SeedResult(seed, False, False, False, None, True, "compile timed out")
        if cret != 0:
            msg = (cstderr or cstdout).strip()
            return SeedResult(seed, False, False, False, cret, False, msg)

        class_name = seed.stem
        run_cmdline = [
            str(java_bin),
            "-Xbatch",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:-TieredCompilation",
            "-XX:+DisplayVMOutputToStderr",
            "-XX:-DisplayVMOutputToStdout",
            "-XX:-UsePerfData",
            "-XX:-LogVMOutput",
            "-XX:+TraceC2Optimizations",
            f"-XX:CompileOnly={class_name}::*",
            "-cp",
            tmpdir,
            class_name,
        ]
        rret, rstdout, rstderr, rtimeout = run_cmd(run_cmdline, runtime_timeout)
        combined = _to_text(rstdout) + "\n" + _to_text(rstderr)
        has_opts = OPT_MARKER in combined

        msg = ""
        if verbose or (rret and rret != 0) or rtimeout:
            snippet = combined.strip()
            msg = snippet[:2000]  # avoid dumping huge logs

        return SeedResult(
            seed,
            True,
            not rtimeout,
            has_opts,
            rret,
            rtimeout,
            msg,
        )


def collect_seeds(seed_dir: Path) -> List[Path]:
    if not seed_dir.is_dir():
        raise SystemExit(f"Seed directory not found: {seed_dir}")
    return sorted(seed_dir.glob("*.java"))


def main() -> int:
    args = parse_args()

    seed_dir = Path(args.seeds).expanduser()
    seeds = collect_seeds(seed_dir)
    if not seeds:
        raise SystemExit(f"No .java seeds found in {seed_dir}")

    java_bin = discover_java_bin(args.java_bin)
    javac_bin = discover_javac_bin(args.javac_bin, java_bin)

    if not args.skip_flag_check:
        check_trace_flag(java_bin)

    out_path = Path(args.output).expanduser() if args.output else None
    out_lock = Lock()
    out_file = None
    if out_path:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_file = out_path.open("w", encoding="utf-8")

    def record_result(res: SeedResult, bucket: List[SeedResult]) -> None:
        bucket.append(res)
        if out_file and res.has_opts:
            with out_lock:
                out_file.write(res.path.name + "\n")
                out_file.flush()

    results: List[SeedResult] = []
    try:
        if args.jobs > 1:
            with ThreadPoolExecutor(max_workers=args.jobs) as pool:
                futures = {
                    pool.submit(
                        process_seed,
                        seed,
                        java_bin,
                        javac_bin,
                        args.compile_timeout,
                        args.timeout,
                        args.verbose,
                    ): seed
                    for seed in seeds
                }
                for fut in as_completed(futures):
                    record_result(fut.result(), results)
        else:
            for seed in seeds:
                res = process_seed(
                    seed,
                    java_bin,
                    javac_bin,
                    args.compile_timeout,
                    args.timeout,
                    args.verbose,
                )
                record_result(res, results)
    finally:
        if out_file:
            out_file.close()

    with_opts = [r for r in results if r.has_opts]
    compile_fail = [r for r in results if not r.compiled]
    runtime_fail = [r for r in results if r.compiled and not r.has_opts and (r.exit_code not in (0, None) or r.timed_out)]

    print(f"Seeds scanned: {len(results)}")
    print(f"  with C2 optimizations: {len(with_opts)}")
    print(f"  compile failures: {len(compile_fail)}")
    if runtime_fail:
        print(f"  runtime failures/timeouts (no opts): {len(runtime_fail)}")

    if with_opts:
        print("\nSeeds with TraceC2Optimizations output:")
        for r in with_opts:
            print(f"  {r.path.name}")

    if out_path:
        print(f"\nIncrementally wrote {len(with_opts)} seed names to {out_path}")

    if args.verbose:
        for r in results:
            if r.message:
                status = "timeout" if r.timed_out else (r.exit_code if r.exit_code is not None else "error")
                print(f"\n[{r.path.name}] status={status}")
                print(r.message)

    if compile_fail or runtime_fail:
        print("\nRe-run with --verbose to inspect failures and VM output.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
