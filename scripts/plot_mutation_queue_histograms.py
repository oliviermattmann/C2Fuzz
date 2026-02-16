#!/usr/bin/env python3
"""
Plot histograms for mutation depth and mutation count from mutation_queue_snapshot.csv.

Outputs two plots per run by default:
  - mutation_depth_hist.png
  - mutation_count_hist.png

Optionally aggregates runs by a grouping key and plots averaged-count histograms.
"""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Tuple

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402


def parse_run_dir_name(run_dir: Path) -> Dict[str, str] | None:
    parts = run_dir.name.split("__")
    if len(parts) < 5:
        return None
    return {
        "timestamp": parts[0],
        "scoring": parts[1],
        "mutator": parts[2],
        "corpus": parts[3],
        "label": run_dir.name,
    }


def collect_run_dirs(root: Path) -> List[Path]:
    return [d for d in root.glob("runs_*_server*/out_*/*__*__*__*__run*") if d.is_dir()]


def load_snapshot(path: Path) -> Dict[str, List[int]] | None:
    snap = path / "mutation_queue_snapshot.csv"
    if not snap.is_file():
        return None
    depths: List[int] = []
    counts: List[int] = []
    with snap.open() as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                depths.append(int(row.get("mutationDepth", "")))
            except (TypeError, ValueError):
                pass
            try:
                counts.append(int(row.get("mutationCount", "")))
            except (TypeError, ValueError):
                pass
    if not depths and not counts:
        return None
    return {"depths": depths, "counts": counts}


def plot_hist(values: List[int], title: str, out: Path, bins: int, log_y: bool) -> None:
    if not values:
        return
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.hist(values, bins=bins, color="#4c78a8", edgecolor="#ffffff", linewidth=0.5)
    ax.set_title(title)
    ax.set_ylabel("Count")
    ax.set_xlabel("Value")
    if log_y:
        ax.set_yscale("log")
    fig.tight_layout()
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=200)
    plt.close(fig)


def plot_hist_avg(values_list: List[List[int]], title: str, out: Path, bins: int, log_y: bool) -> None:
    values_list = [v for v in values_list if v]
    if not values_list:
        return
    all_vals = [v for sub in values_list for v in sub]
    if not all_vals:
        return
    min_v, max_v = min(all_vals), max(all_vals)
    if min_v == max_v:
        bin_edges = np.array([min_v - 0.5, max_v + 0.5])
    else:
        bin_edges = np.linspace(min_v, max_v, bins + 1)
    counts = []
    for vals in values_list:
        hist, _ = np.histogram(vals, bins=bin_edges)
        counts.append(hist)
    avg_counts = np.mean(np.stack(counts, axis=0), axis=0)
    widths = np.diff(bin_edges)
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.bar(bin_edges[:-1], avg_counts, width=widths, align="edge", color="#4c78a8", edgecolor="#ffffff", linewidth=0.5)
    ax.set_title(title)
    ax.set_ylabel("Avg count per run")
    ax.set_xlabel("Value")
    if log_y:
        ax.set_yscale("log")
    fig.tight_layout()
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=200)
    plt.close(fig)


def safe_name(s: str) -> str:
    return s.replace("/", "_").replace(" ", "_")


def main() -> int:
    ap = argparse.ArgumentParser(description="Plot mutation queue depth/count histograms.")
    ap.add_argument(
        "--root",
        default="evaluation",
        help="Root directory containing runs_*_server* (default: evaluation)",
    )
    ap.add_argument(
        "--out",
        default="plots/mutation_queue_hist",
        help="Output directory (default: plots/mutation_queue_hist)",
    )
    ap.add_argument(
        "--bins",
        type=int,
        default=50,
        help="Number of histogram bins (default: 50)",
    )
    ap.add_argument(
        "--log-y",
        action="store_true",
        help="Use log scale for Y axis.",
    )
    ap.add_argument(
        "--aggregate-by",
        choices=["none", "config", "scoring-corpus", "scoring", "corpus"],
        default="none",
        help="Aggregate across runs and plot combined histograms per group.",
    )
    ap.add_argument(
        "--aggregate",
        action="store_true",
        help="Deprecated; same as --aggregate-by config.",
    )
    args = ap.parse_args()

    if args.aggregate and args.aggregate_by == "none":
        args.aggregate_by = "config"

    root = Path(args.root).resolve()
    out_root = Path(args.out).resolve()

    run_dirs = collect_run_dirs(root)
    if not run_dirs:
        raise SystemExit(f"No run dirs found under {root}")

    agg: Dict[Tuple[str, str, str], Dict[str, List[List[int]]]] = defaultdict(lambda: {"depths": [], "counts": []})

    for d in run_dirs:
        meta = parse_run_dir_name(d)
        if not meta:
            continue
        snapshot = load_snapshot(d)
        if not snapshot:
            continue
        tag = safe_name(f"{meta['scoring']}__{meta['corpus']}__{meta['mutator']}")
        title_suffix = f"{meta['scoring']}/{meta['corpus']}/{meta['mutator']}"
        plot_hist(
            snapshot["depths"],
            f"Mutation depth | {title_suffix}",
            out_root / f"mutation_depth_hist__{tag}.png",
            args.bins,
            args.log_y,
        )
        plot_hist(
            snapshot["counts"],
            f"Mutation count | {title_suffix}",
            out_root / f"mutation_count_hist__{tag}.png",
            args.bins,
            args.log_y,
        )

        key = (meta["scoring"], meta["corpus"], meta["mutator"])
        agg[key]["depths"].append(snapshot["depths"])
        agg[key]["counts"].append(snapshot["counts"])

    if args.aggregate_by != "none" and agg:
        grouped: Dict[Tuple[str, ...], Dict[str, List[List[int]]]] = defaultdict(lambda: {"depths": [], "counts": []})
        for (scoring, corpus, mutator), vals in agg.items():
            if args.aggregate_by == "config":
                key = (scoring, corpus, mutator)
            elif args.aggregate_by == "scoring-corpus":
                key = (scoring, corpus)
            elif args.aggregate_by == "scoring":
                key = (scoring,)
            else:
                key = (corpus,)
            grouped[key]["depths"].extend(vals["depths"])
            grouped[key]["counts"].extend(vals["counts"])

        agg_out = out_root / "aggregate"
        for key, vals in grouped.items():
            if args.aggregate_by == "config":
                scoring, corpus, mutator = key
                tag = safe_name(f"{scoring}__{corpus}__{mutator}")
                title_suffix = f"{scoring}/{corpus}/{mutator}"
            elif args.aggregate_by == "scoring-corpus":
                scoring, corpus = key
                tag = safe_name(f"{scoring}__{corpus}__all_mutators")
                title_suffix = f"{scoring}/{corpus}/all-mutators"
            elif args.aggregate_by == "scoring":
                (scoring,) = key
                tag = safe_name(f"{scoring}__all_corpus__all_mutators")
                title_suffix = f"{scoring}/all-corpus/all-mutators"
            else:
                (corpus,) = key
                tag = safe_name(f"{corpus}__all_scoring__all_mutators")
                title_suffix = f"{corpus}/all-scoring/all-mutators"

            plot_hist_avg(
                vals["depths"],
                f"Mutation depth | {title_suffix}",
                agg_out / f"mutation_depth_hist__{tag}.png",
                args.bins,
                args.log_y,
            )
            plot_hist_avg(
                vals["counts"],
                f"Mutation count | {title_suffix}",
                agg_out / f"mutation_count_hist__{tag}.png",
                args.bins,
                args.log_y,
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
