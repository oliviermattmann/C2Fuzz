#!/usr/bin/env python3
"""
Visualize end-of-run mutation_queue_snapshot.csv.

For each scoring × corpus combination:
  - seeds% bar
  - histogram of mutationDepth
  - histogram of mutationCount

Outputs PNGs under the given prefix.

Usage:
  python3 scripts/plot_mutation_queue.py --root sweep120/fuzz_sessions --output-prefix mq
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Tuple

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402


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
                    "scoring": run.get("scoring_mode", "").lower(),
                    "mutator": run.get("mutator_policy", "").lower(),
                    "corpus": run.get("corpus_policy", "").lower(),
                    "path": dest,
                }
            )
    else:
        for snap in root.rglob("mutation_queue_snapshot.csv"):
            run_dir = snap.parent
            parts = run_dir.name.split("__")
            scoring = parts[0].split("_", 1)[-1].lower() if len(parts) >= 3 else ""
            mutator = parts[1].lower() if len(parts) >= 3 else ""
            corpus = parts[2].lower() if len(parts) >= 3 else ""
            runs.append({"scoring": scoring, "mutator": mutator, "corpus": corpus, "path": run_dir})
    return runs


def summarize_snapshot(path: Path) -> Tuple[int, int, Counter, Counter]:
    snap = path / "mutation_queue_snapshot.csv"
    if not snap.is_file():
        return 0, 0, Counter(), Counter()
    rows = list(csv.DictReader(snap.open()))
    if not rows:
        return 0, 0, Counter(), Counter()
    total = len(rows)
    seeds = 0
    depths = Counter()
    counts = Counter()
    for row in rows:
        try:
            depth = int(float(row.get("mutationDepth", 0)))
            count = int(float(row.get("mutationCount", 0)))
        except ValueError:
            depth = 0
            count = 0
        depths[depth] += 1
        counts[count] += 1
        if count == 0:
            seeds += 1
    return total, seeds, depths, counts


def prepare_hist(counter: Counter, clip_percent: float | None) -> Tuple[List[int], List[int]]:
    if not counter:
        return [], []
    values = []
    for k, v in counter.items():
        values.extend([k] * v)
    if clip_percent and clip_percent > 0 and clip_percent < 100:
        cap = int(np.percentile(values, clip_percent))
        below = Counter()
        overflow = 0
        for k, v in counter.items():
            if k > cap:
                overflow += v
            else:
                below[k] += v
        xs = sorted(below.keys())
        ys = [below[x] for x in xs]
        if overflow:
            xs.append(cap + 1)
            ys.append(overflow)
        return xs, ys
    else:
        xs = sorted(counter.keys())
        ys = [counter[x] for x in xs]
        return xs, ys


def plot_group(
    scoring: str,
    corpus: str,
    seeds_pct: float,
    depths: Counter,
    counts: Counter,
    out_prefix: Path,
    depth_xlim=None,
    count_xlim=None,
    logy=False,
    clip_percent: float | None = None,
) -> None:
    fig, axes = plt.subplots(1, 3, figsize=(12, 3))
    # Seeds bar
    axes[0].bar([f"{scoring}\n{corpus}"], [seeds_pct], color="#4C78A8")
    axes[0].set_ylim(0, 100)
    axes[0].set_ylabel("Seeds in queue (%)")
    axes[0].set_title("Seeds")

    # Depth histogram
    xs, ys = prepare_hist(depths, clip_percent)
    axes[1].bar(xs, ys, color="#59A14F")
    axes[1].set_xlabel("mutationDepth")
    axes[1].set_ylabel("Count")
    axes[1].set_title("Depth")
    if depth_xlim:
        axes[1].set_xlim(0, depth_xlim)
    if logy:
        axes[1].set_yscale("log")

    # Count histogram
    xc, yc = prepare_hist(counts, clip_percent)
    axes[2].bar(xc, yc, color="#E15759")
    axes[2].set_xlabel("mutationCount")
    axes[2].set_ylabel("Count")
    axes[2].set_title("Count")
    if count_xlim:
        axes[2].set_xlim(0, count_xlim)
    if logy:
        axes[2].set_yscale("log")

    fig.suptitle(f"{scoring} | {corpus}", fontsize=10)
    fig.tight_layout()
    out = out_prefix.with_name(f"{out_prefix.name}_{scoring}_{corpus}.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"[ok] wrote {out}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Plot mutation queue summaries grouped by scoring x corpus.")
    ap.add_argument("--root", required=True, help="Root containing runs.")
    ap.add_argument("--output-prefix", default="mq", help="Output prefix for PNGs.")
    ap.add_argument("--depth-xlim", type=int, help="Optional max x-axis value for mutationDepth histograms.")
    ap.add_argument("--count-xlim", type=int, help="Optional max x-axis value for mutationCount histograms.")
    ap.add_argument("--logy", action="store_true", help="Plot depth/count histograms with log-scale y-axis.")
    ap.add_argument("--clip-percent", type=float, default=99.0, help="Percentile clipping for histograms (default: 99). Set to 0 to disable.")
    args = ap.parse_args()

    runs = collect_runs(Path(args.root).resolve())
    if not runs:
        raise SystemExit("No runs found.")

    grouped = defaultdict(list)
    for r in runs:
        total, seeds, depths, counts = summarize_snapshot(r["path"])
        if total == 0:
            continue
        grouped[(r["scoring"], r["corpus"])].append((total, seeds, depths, counts))

    for (scoring, corpus), items in grouped.items():
        total_sum = sum(t for t, _, _, _ in items)
        seeds_sum = sum(s for _, s, _, _ in items)
        seeds_pct = seeds_sum / total_sum * 100 if total_sum else 0
        depth_agg = Counter()
        count_agg = Counter()
        for _, _, d, c in items:
            depth_agg.update(d)
            count_agg.update(c)
        plot_group(
            scoring,
            corpus,
            seeds_pct,
            depth_agg,
            count_agg,
            Path(args.output_prefix),
            depth_xlim=args.depth_xlim,
            count_xlim=args.count_xlim,
            logy=args.logy,
            clip_percent=args.clip_percent if args.clip_percent > 0 else None,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
