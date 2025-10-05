#!/usr/bin/env python3
import argparse
from pathlib import Path

def scan_file(path: Path) -> bool:
    bad = False
    with path.open("rb") as fh:
        for lineno, raw in enumerate(fh, start=1):
            if any(b > 0x7F for b in raw):
                bad = True
                # decode best-effort so you can see the characters
                text = raw.decode("utf-8", errors="replace").rstrip("\n")
                print(f"{path}:{lineno}: {text}")
    return bad

def main():
    parser = argparse.ArgumentParser(description="Find non-ASCII bytes in files.")
    parser.add_argument("paths", nargs="+", help="Files or directories to scan")
    args = parser.parse_args()

    offenders = False
    for p in map(Path, args.paths):
        if p.is_dir():
            for path in p.rglob("*"):
                if path.is_file() and scan_file(path):
                    offenders = True
        elif p.is_file():
            if scan_file(p):
                offenders = True
        else:
            print(f"Skipping missing path: {p}")

    if not offenders:
        print("No non-ASCII characters found.")

if __name__ == "__main__":
    main()
