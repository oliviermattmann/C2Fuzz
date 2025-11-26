#!/usr/bin/env python3
"""
Summarize end-of-run mutation_queue_snapshot.csv.

For each run:
  - total entries
  - initial seeds remaining (heuristic: mutationCount == 0)
  - histogram of mutationCount and mutationDepth

Aggregates by scoring x corpus.

Usage:
  python3 scripts/analyze_mutation_queue.py --root sweep120/fuzz_sessions
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List


def collect_runs(root: Path) -> List[Dict]:
    runs: List[Dict] = []
    manifest = root / "manifest.json"
    if manifest.is_file():
        data = json.loads(manifest.read_text())
        for run in data.get("runs", []):
            dest = Path(run.get("dest_dir", ""))
            if not dest.exists():
                alt = manifest.parent / dest.name
                if alt.exists():
                    dest = alt
            if not dest:
                continue
            runs.append(
                {
                    "scoring": run.get("scoring_mode", ""),
                    "mutator": run.get("mutator_policy", ""),
                    "corpus": run.get("corpus_policy", ""),
                    "label": run.get("label", dest.name),
                    "path": dest,
                }
            )
    else:
        for snap in root.rglob("mutation_queue_snapshot.csv"):
            run_dir = snap.parent
            parts = run_dir.name.split("__")
            scoring = parts[0].split("_", 1)[-1] if len(parts) >= 3 else ""
            mutator = parts[1] if len(parts) >= 3 else ""
            corpus = parts[2] if len(parts) >= 3 else ""
            runs.append({"scoring": scoring, "mutator": mutator, "corpus": corpus, "label": run_dir.name, "path": run_dir})
    return runs


def summarize_snapshot(path: Path) -> Dict:
    snap = path / "mutation_queue_snapshot.csv"
    if not snap.is_file():
        return {}
    rows = list(csv.DictReader(snap.open()))
    if not rows:
        return {}
    total = len(rows)
    seeds = 0
    depth_counter = Counter()
    count_counter = Counter()
    for row in rows:
        try:
            depth = int(float(row.get("mutationDepth", 0)))
            count = int(float(row.get("mutationCount", 0)))
        except ValueError:
            depth = 0
            count = 0
        depth_counter[depth] += 1
        count_counter[count] += 1
        if count == 0:
            seeds += 1
    return {
        "total": total,
        "seeds": seeds,
        "depth_counter": depth_counter,
        "count_counter": count_counter,
    }


def format_top(counter: Counter, top: int = 5) -> str:
    items = counter.most_common(top)
    return ", ".join(f"{k}:{v}" for k, v in items)


def main() -> int:
    ap = argparse.ArgumentParser(description="Analyze mutation_queue_snapshot.csv across runs.")
    ap.add_argument("--root", required=True, help="Root directory with runs.")
    args = ap.parse_args()

    runs = collect_runs(Path(args.root).resolve())
    if not runs:
        raise SystemExit("No runs found.")

    grouped = defaultdict(list)
    for r in runs:
        summary = summarize_snapshot(r["path"])
        if not summary:
            continue
        r.update(summary)
        grouped[(r["scoring"], r["corpus"])].append(r)

    print("Per-run:")
    for r in runs:
        if "total" not in r:
            continue
        seeds_pct = r["seeds"] / r["total"] * 100 if r["total"] else 0
        print(f"{r['label']}: total {r['total']}, seeds {r['seeds']} ({seeds_pct:.1f}%), depth top {format_top(r['depth_counter'])}, count top {format_top(r['count_counter'])}")

    print("\nAggregated by scoring x corpus:")
    for key, rs in grouped.items():
        scoring, corpus = key
        avg_total = sum(r["total"] for r in rs) / len(rs)
        avg_seeds = sum(r["seeds"] for r in rs) / len(rs)
        seeds_pct = avg_seeds / avg_total * 100 if avg_total else 0
        # merge counters
        depth_all = Counter()
        count_all = Counter()
        for r in rs:
            depth_all.update(r["depth_counter"])
            count_all.update(r["count_counter"])
        print(f"{scoring} / {corpus}: n={len(rs)} avg_total {avg_total:.1f}, avg_seeds {avg_seeds:.1f} ({seeds_pct:.1f}%), depth top {format_top(depth_all)}, count top {format_top(count_all)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
