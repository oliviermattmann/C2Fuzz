#!/usr/bin/env python3
"""
flatten_java_no_rename.py — Copy all .java files into a flat directory without renaming.

Usage:
  python flatten_java_no_rename.py /path/to/source /path/to/flat-dest

Behavior:
  - Recursively finds *.java under source.
  - Copies each file into the destination directory (created if needed).
  - If a filename already exists in the destination, it is SKIPPED and an alert is printed.
  - Preserves file metadata (timestamps) on copy.
"""

from __future__ import annotations
import argparse
from pathlib import Path
import shutil
import sys

def flatten_java_no_rename(source: Path, dest: Path) -> tuple[int, int]:
    if not source.is_dir():
        raise NotADirectoryError(f"Source is not a directory: {source}")
    dest.mkdir(parents=True, exist_ok=True)

    copied = 0
    skipped = 0

    for java_file in source.rglob("*.java"):
        if not java_file.is_file():
            continue

        target = dest / java_file.name

        if target.exists():
            # Alert and skip
            print(
                f"[ALERT] Skipping '{java_file}' -> '{target.name}': "
                f"destination file already exists at '{target}'.",
                file=sys.stderr,
            )
            skipped += 1
            continue

        try:
            shutil.copy2(java_file, target)  # preserve metadata
            copied += 1
        except Exception as e:
            print(f"[ERROR] Failed to copy '{java_file}' -> '{target}': {e}", file=sys.stderr)

    return copied, skipped

def main(argv=None):
    parser = argparse.ArgumentParser(description="Copy all .java files into a flat directory without renaming.")
    parser.add_argument("source", type=Path, help="Path to the source directory to scan")
    parser.add_argument("dest", type=Path, help="Path to the destination flat directory")
    args = parser.parse_args(argv)

    try:
        copied, skipped = flatten_java_no_rename(args.source.resolve(), args.dest.resolve())
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Copied: {copied} file(s)")
    print(f"Skipped due to existing filename: {skipped} file(s)")
    print(f"Destination: {args.dest.resolve()}")

if __name__ == "__main__":
    main()
