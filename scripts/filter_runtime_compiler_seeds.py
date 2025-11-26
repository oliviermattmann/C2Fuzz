#!/usr/bin/env python3
"""
Filter JDK hotspot jtreg runtime/compiler tests down to self-contained seeds.

For each .java file under jdk/test/hotspot/jtreg/{runtime,compiler}, try to
compile it in isolation. By default this uses the javac_server on port 8090
for speed; you can fall back to a local javac via --javac.

If compilation succeeds, copy the source into seeds/runtime_compiler using the
original filename. If a filename collision occurs (same basename from different
paths), the later file is skipped to preserve public class/file-name alignment.

Usage:
  python3 scripts/filter_runtime_compiler_seeds.py \
      --jdk-tests jdk/test/hotspot/jtreg \
      --output seeds/runtime_compiler \
      --server http://127.0.0.1:8090/compile   # default
      [--javac /path/to/javac]                 # fallback

Defaults:
  jdk-tests: jdk/test/hotspot/jtreg (relative to repo root)
  output: seeds/runtime_compiler
  server: http://127.0.0.1:8090/compile
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Optional

import json
import urllib.error
import urllib.request


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Filter self-contained jtreg runtime/compiler tests as seeds.")
    ap.add_argument(
        "--jdk-tests",
        default="jdk/test/hotspot/jtreg",
        help="Path to jtreg tests root (default: jdk/test/hotspot/jtreg).",
    )
    ap.add_argument(
        "--output",
        default="seeds/runtime_compiler",
        help="Output directory for accepted seeds (default: seeds/runtime_compiler).",
    )
    ap.add_argument(
        "--server",
        default="http://127.0.0.1:8090/compile",
        help="javac_server compile endpoint (default: http://127.0.0.1:8090/compile).",
    )
    ap.add_argument(
        "--javac",
        default=None,
        help="Fallback path to javac (used if server is unreachable).",
    )
    return ap.parse_args()


def try_compile_server(server: str, source: Path, class_output: Optional[Path]) -> bool:
    payload = {"sourcePath": str(source)}
    if class_output:
        payload["classOutput"] = str(class_output)
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        server,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8")
            parsed = json.loads(body)
            return parsed.get("success") is True
    except Exception:
        return False


def try_compile_javac(javac_bin: str, source: Path) -> bool:
    try:
        res = subprocess.run([javac_bin, str(source)], capture_output=True, timeout=60)
        return res.returncode == 0
    except Exception:
        return False


def strip_package(source_path: Path) -> None:
    try:
        lines = source_path.read_text(encoding="utf-8").splitlines()
    except Exception:
        return
    stripped: list[str] = []
    for line in lines:
        if line.strip().startswith("package "):
            continue
        stripped.append(line)
    try:
        source_path.write_text("\n".join(stripped) + "\n", encoding="utf-8")
    except Exception:
        pass


def main() -> int:
    args = parse_args()
    javac_bin = args.javac
    if not javac_bin and "JAVAC_BIN" in os.environ:
        javac_bin = os.environ["JAVAC_BIN"]
    if not javac_bin:
        javac_bin = shutil.which("javac")
    use_server = args.server

    root = Path(args.jdk_tests).resolve()
    runtime_dir = root / "runtime"
    compiler_dir = root / "compiler"
    output_dir = Path(args.output).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    sources = list(runtime_dir.rglob("*.java")) + list(compiler_dir.rglob("*.java"))
    kept = 0
    total = 0
    seen_names: set[str] = set()

    for idx, src in enumerate(sorted(sources), 1):
        total += 1
        if idx % 500 == 0 or idx == len(sources):
            print(f"[{idx}/{len(sources)}] processing {src}")
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            tmpfile = tmpdir / src.name
            try:
                shutil.copy2(src, tmpfile)
            except Exception:
                continue
            strip_package(tmpfile)
            ok = False
            # Prefer server; fall back to javac if available
            if use_server:
                ok = try_compile_server(use_server, tmpfile, tmpdir)
            if not ok and javac_bin:
                ok = try_compile_javac(javac_bin, tmpfile)
            if not ok:
                continue
            target_name = src.name
            if target_name in seen_names:
                continue  # skip duplicate basenames to keep public class alignment
            seen_names.add(target_name)
            shutil.copy2(tmpfile, output_dir / target_name)
            kept += 1

    print(f"Checked {total} sources, kept {kept} self-contained seeds in {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
