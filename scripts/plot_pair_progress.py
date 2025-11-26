#!/usr/bin/env python3
"""
Plot how discovered optimization pairs grow over time, grouped by
mutator scheduling and corpus management.

For each (mutator_policy, corpus_policy) combination, emits a plot with one
line per scoring mode showing unique_pairs over elapsed time.

Usage:
  python3 scripts/plot_pair_progress.py \
      --root sweep120/fuzz_sessions \
      --output-prefix pairs_progress \
      --metric ratio
"""

from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path
from typing import Dict, List, Tuple

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402


def parse_from_dirname(name: str) -> Dict[str, str]:
    # Expected patterns like: 001_interaction_pair_weighted__uniform__champion__run01
    parts = name.split("__")
    scoring = mutator = corpus = None
    if len(parts) >= 3:
        first = re.sub(r"^\d+_", "", parts[0])
        scoring = first or None
        mutator = parts[1] or None
        corpus = parts[2] or None
    return {
        "label": name,
        "scoring_mode": scoring,
        "mutator_policy": mutator,
        "corpus_policy": corpus,
    }


def discover_runs(root: Path) -> List[Dict[str, str]]:
    manifest = root / "manifest.json"
    runs: List[Dict[str, str]] = []
    if manifest.is_file():
        import json

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
                    "label": run.get("label", dest.name),
                    "scoring_mode": run.get("scoring_mode"),
                    "mutator_policy": run.get("mutator_policy"),
                    "corpus_policy": run.get("corpus_policy"),
                    "path": str(dest),
                }
            )
    else:
        for sig in root.rglob("signals.csv"):
            run_dir = sig.parent
            meta = parse_from_dirname(run_dir.name)
            meta["path"] = str(run_dir)
            runs.append(meta)
    return runs


def load_signal(path: Path, metric: str) -> Tuple[List[float], List[float]]:
    with path.open() as f:
        reader = csv.DictReader(f)
        t = []
        y = []
        for row in reader:
            try:
                elapsed = float(row.get("elapsed_seconds", 0.0))
                unique_pairs = float(row.get("unique_pairs", 0.0))
                total_pairs = float(row.get("total_pairs", 0.0))
            except ValueError:
                continue
            t.append(elapsed / 3600.0)  # hours
            if metric == "ratio":
                y.append(unique_pairs / total_pairs if total_pairs else 0.0)
            else:
                y.append(unique_pairs)
    return t, y


def plot_group(group_key: Tuple[str, str], series: Dict[str, Tuple[List[float], List[float]]], metric: str, out: Path) -> None:
    if not series:
        return
    plt.figure(figsize=(8, 5))
    for scoring, (t, y) in sorted(series.items()):
        if not t:
            continue
        plt.plot(t, y, label=scoring)
    plt.xlabel("Elapsed time (hours)")
    if metric == "ratio":
        plt.ylabel("Unique pairs / total")
        plt.ylim(0, 1.0)
    else:
        plt.ylabel("Unique pairs")
    mut, corpus = group_key
    plt.title(f"Mutator={mut}, Corpus={corpus}")
    plt.grid(True, alpha=0.3)
    plt.legend(title="scoring", fontsize=8)
    plt.tight_layout()
    out.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(out, dpi=150)
    plt.close()
    print(f"[ok] wrote {out}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Plot unique pair discovery over time by scoring, grouped by mutator/corpus.")
    ap.add_argument("--root", required=True, help="Run directory or root containing multiple runs.")
    ap.add_argument("--output-prefix", default="pairs_progress", help="Prefix for output PNGs.")
    ap.add_argument(
        "--metric",
        choices=["ratio", "raw"],
        default="ratio",
        help="Plot unique_pairs/total_pairs (ratio) or raw unique_pairs.",
    )
    args = ap.parse_args()

    runs = discover_runs(Path(args.root).resolve())
    if not runs:
        raise SystemExit(f"No runs found under {args.root}")

    groups: Dict[Tuple[str, str], Dict[str, Tuple[List[float], List[float]]]] = {}
    for meta in runs:
        mut = (meta.get("mutator_policy") or "unknown").lower()
        corpus = (meta.get("corpus_policy") or "unknown").lower()
        scoring = (meta.get("scoring_mode") or "unknown").lower()
        sig_path = Path(meta["path"]) / "signals.csv"
        if not sig_path.is_file():
            continue
        t, y = load_signal(sig_path, args.metric)
        groups.setdefault((mut, corpus), {})[scoring] = (t, y)

    for (mut, corpus), series in sorted(groups.items()):
        out = Path(f"{args.output_prefix}_{mut}_{corpus}.png")
        plot_group((mut, corpus), series, args.metric, out)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
