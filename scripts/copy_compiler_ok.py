#!/usr/bin/env python3
"""Copy compiler jtreg seed files listed in ok_result-26_2.txt into jtreg_compiler_ok."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


def copy_compiler_tests(results_path: Path, source_dir: Path, target_dir: Path) -> None:
    """Copy unique compiler tests from source_dir into target_dir based on results list."""
    if not results_path.is_file():
        raise FileNotFoundError(f"Results file not found: {results_path}")

    if not source_dir.is_dir():
        raise NotADirectoryError(f"Seed directory not found: {source_dir}")

    target_dir.mkdir(parents=True, exist_ok=True)

    copied = 0
    missing: list[str] = []
    seen: set[str] = set()

    for raw_line in results_path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "/compiler/" not in line:
            continue

        filename = Path(line).name
        if filename in seen:
            continue
        seen.add(filename)

        source_file = source_dir / filename
        if not source_file.is_file():
            missing.append(filename)
            continue

        shutil.copy2(source_file, target_dir / filename)
        copied += 1

    print(f"Copied {copied} file(s) to {target_dir}")
    if missing:
        print("Missing files (not found in source):")
        for name in missing:
            print(f"  {name}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Copy compiler jtreg seeds listed in a results file")
    parser.add_argument(
        "results",
        nargs="?",
        default="ok_result-26_2.txt",
        help="Path to the file containing absolute jtreg test paths (default: %(default)s)",
    )
    parser.add_argument(
        "--source",
        default="jtreg_seeds",
        help="Directory containing extracted jtreg seed Java files (default: %(default)s)",
    )
    parser.add_argument(
        "--dest",
        default="jtreg_compiler_ok",
        help="Destination directory for the copied Java files (default: %(default)s)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    results_path = Path(args.results).expanduser().resolve()
    source_dir = Path(args.source).expanduser().resolve()
    target_dir = Path(args.dest).expanduser().resolve()

    copy_compiler_tests(results_path, source_dir, target_dir)


if __name__ == "__main__":
    main()
