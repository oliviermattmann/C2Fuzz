#!/usr/bin/env python3
"""
Minimal command-line client for the javac server.

Usage:
    ./compile_client.py Foo.java --server http://127.0.0.1:8090/compile
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import urllib.error
import urllib.request


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Send a single-file compile request to the javac server."
    )
    parser.add_argument(
        "source",
        type=pathlib.Path,
        help="Path to the Java source file to compile.",
    )
    parser.add_argument(
        "--server",
        default="http://127.0.0.1:8090/compile",
        help="Compile endpoint URL (default: %(default)s).",
    )
    parser.add_argument(
        "--class-output",
        type=pathlib.Path,
        help="Optional class output directory; defaults to the source file's directory.",
    )
    parser.add_argument(
        "--option",
        action="append",
        default=[],
        dest="options",
        metavar="FLAG",
        help="Additional compiler flag. Repeat for multiple flags.",
    )
    parser.add_argument(
        "--raw-response",
        action="store_true",
        help="Print the raw JSON response without pretty formatting.",
    )
    return parser.parse_args(argv)


def build_payload(args: argparse.Namespace) -> dict:
    source_path = args.source.expanduser().resolve()
    if not source_path.exists():
        raise SystemExit(f"Source file not found: {source_path}")
    if not source_path.is_file():
        raise SystemExit(f"Source path is not a file: {source_path}")

    payload = {"sourcePath": str(source_path)}

    if args.class_output:
        payload["classOutput"] = str(args.class_output.expanduser().resolve())

    if args.options:
        payload["options"] = args.options

    return payload


def send_request(url: str, payload: dict) -> tuple[int, str]:
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request) as response:
            status = response.status
            body = response.read().decode("utf-8")
            return status, body
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        return error.code, body
    except urllib.error.URLError as error:
        raise SystemExit(f"Failed to reach server: {error.reason}") from error


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    payload = build_payload(args)
    status, body = send_request(args.server, payload)

    if args.raw_response:
        print(body)
    else:
        try:
            parsed = json.loads(body)
            print(json.dumps(parsed, indent=2, sort_keys=True))
        except json.JSONDecodeError:
            print(body)

    if status >= 400:
        print(f"Server returned HTTP {status}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
