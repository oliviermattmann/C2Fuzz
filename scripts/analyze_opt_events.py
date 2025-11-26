#!/usr/bin/env python3
"""
Summarize optimization events from mutator_optimization_stats.csv.

For each run, reports:
  - Total optimization events (inc+dec)
  - Evenness of feature distribution (normalized entropy + max-share)
  - Top-N features overall
  - Top-N mutators by optimization events, with their top features

Usage:
  python3 scripts/analyze_opt_events.py --root sweep120/fuzz_sessions --top 5
"""

from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Tuple


def discover_runs(root: Path) -> List[Path]:
    if (root / "mutator_optimization_stats.csv").is_file():
        return [root]
    return sorted({p.parent for p in root.rglob("mutator_optimization_stats.csv")})


def feature_columns(header: Iterable[str]) -> List[Tuple[str, str]]:
    cols = []
    for name in header:
        if not name.startswith("OptEvent_"):
            continue
        if name.endswith("_inc"):
            cols.append((name[len("OptEvent_") : -len("_inc")], "inc"))
        elif name.endswith("_dec"):
            cols.append((name[len("OptEvent_") : -len("_dec")], "dec"))
    return cols


def normalized_entropy(counts: Dict[str, float]) -> float:
    total = sum(counts.values())
    if total <= 0:
        return 0.0
    probs = [c / total for c in counts.values() if c > 0]
    if len(probs) <= 1:
        return 0.0
    h = -sum(p * math.log(p) for p in probs)
    return h / math.log(len(probs))


def analyze_run(run_dir: Path, top: int) -> None:
    path = run_dir / "mutator_optimization_stats.csv"
    if not path.is_file():
        print(f"[skip] {run_dir}: mutator_optimization_stats.csv missing")
        return

    with path.open(newline="") as f:
        reader = csv.DictReader(f)
        feats = feature_columns(reader.fieldnames or [])
        if not feats:
            print(f"[skip] {run_dir}: no OptEvent_* columns found")
            return

        feature_totals: Dict[str, float] = defaultdict(float)
        by_mutator: Dict[str, Dict[str, float]] = defaultdict(lambda: defaultdict(float))

        for row in reader:
            mut = row.get("mutator", "UNKNOWN")
            for feat, kind in feats:
                val = row.get(f"OptEvent_{feat}_{kind}", "0")
                try:
                    num = float(val)
                except ValueError:
                    num = 0.0
                feature_totals[feat] += num
                by_mutator[mut][feat] += num

    total_events = sum(feature_totals.values())
    even = normalized_entropy(feature_totals)
    max_share = max(feature_totals.values()) / total_events if total_events else 0.0
    top_feats = sorted(feature_totals.items(), key=lambda kv: kv[1], reverse=True)[:top]

    print(f"Run: {run_dir}")
    print(f"  total opt events: {total_events:.0f}")
    print(f"  feature evenness (norm entropy): {even:.3f}, max_share: {max_share:.2f}")
    print("  top features:")
    for feat, count in top_feats:
        share = (count / total_events) if total_events else 0
        print(f"    {feat:35s} {count:10.0f} ({share:5.1%})")

    mut_totals = {m: sum(d.values()) for m, d in by_mutator.items()}
    for mut, mtotal in sorted(mut_totals.items(), key=lambda kv: kv[1], reverse=True)[:top]:
        feats_sorted = sorted(by_mutator[mut].items(), key=lambda kv: kv[1], reverse=True)[: min(3, top)]
        print(f"  mutator {mut}: {mtotal:.0f} events | top feats: " + ", ".join(f"{f} ({c:.0f})" for f, c in feats_sorted))
    print()


def main() -> int:
    ap = argparse.ArgumentParser(description="Analyze optimization event distribution and mutator impact.")
    ap.add_argument("--root", required=True, help="Run directory or root containing multiple runs.")
    ap.add_argument("--top", type=int, default=5, help="How many top features/mutators to show.")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    runs = discover_runs(root)
    if not runs:
        raise SystemExit(f"No runs with mutator_optimization_stats.csv found under {root}")

    for run in runs:
        analyze_run(run, args.top)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
