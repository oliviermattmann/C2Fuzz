#!/usr/bin/env python3
"""
Quick visualization helper for C2Fuzz run artifacts.

Generates PNGs under each run directory using:
  - signals.csv (time-series)
  - mutation_queue_snapshot.csv (queue depth, seed retention)

Usage:
  python3 scripts/plot_run_metrics.py \
    --run-dir sweep120/fuzz_sessions/exp_full120_pf_idf/001_pf_idf__uniform__champion__run01 \
    --seeds-dir /path/to/seeds \
    --output-prefix plots_

If --run-dir points to a directory containing signals.csv, that run is plotted.
If it points to a root containing multiple runs, all subdirectories with signals.csv are processed.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set

import matplotlib.pyplot as plt
import pandas as pd


def discover_runs(root: Path) -> List[Path]:
    if (root / "signals.csv").is_file():
        return [root]
    return sorted({p.parent for p in root.rglob("signals.csv")})


def plot_signals(run: Path, prefix: str) -> None:
    sig_path = run / "signals.csv"
    if not sig_path.is_file():
        print(f"[skip] {run}: signals.csv missing")
        return
    df = pd.read_csv(sig_path)
    if df.empty:
        print(f"[skip] {run}: signals.csv empty")
        return
    t_hours = df["elapsed_seconds"] / 3600.0

    fig, axes = plt.subplots(1, 2, figsize=(12, 4))
    axes[0].plot(t_hours, df["avg_exec_runtime_ms"], label="avg_exec_runtime_ms")
    axes[0].set_xlabel("hours")
    axes[0].set_ylabel("avg_exec_runtime_ms")
    axes[0].grid(True, alpha=0.3)
    axes[0].set_title("Execution time")

    axes[1].plot(t_hours, df["corpus_accepted"], label="accepted")
    if "corpus_replaced" in df:
        axes[1].plot(t_hours, df["corpus_replaced"], label="replaced")
    axes[1].set_xlabel("hours")
    axes[1].set_ylabel("decisions (cumulative)")
    axes[1].set_title("Corpus decisions")
    axes[1].legend()
    axes[1].grid(True, alpha=0.3)

    fig.suptitle(run.name, fontsize=10)
    fig.tight_layout()
    out = run / f"{prefix}signals.png"
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"[ok ] wrote {out}")


def load_seed_names(seeds_dir: Optional[Path]) -> Set[str]:
    if not seeds_dir or not seeds_dir.is_dir():
        return set()
    names = set()
    for path in seeds_dir.rglob("*.java"):
        names.add(path.stem)
    return names


def plot_queue(run: Path, prefix: str, seeds: Set[str]) -> None:
    q_path = run / "mutation_queue_snapshot.csv"
    if not q_path.is_file():
        print(f"[skip] {run}: mutation_queue_snapshot.csv missing")
        return
    df = pd.read_csv(q_path)
    if df.empty:
        print(f"[skip] {run}: mutation_queue_snapshot.csv empty")
        return

    # Depth histogram
    fig, ax = plt.subplots(figsize=(6, 4))
    depths = df["mutationDepth"].astype(float)
    ax.hist(depths, bins=30, color="#4C78A8", alpha=0.8)
    ax.axvline(depths.mean(), color="red", linestyle="--", label=f"mean={depths.mean():.2f}")
    ax.set_xlabel("mutationDepth")
    ax.set_ylabel("count")
    ax.set_title(f"{run.name}: mutation depth")
    ax.legend()
    ax.grid(True, alpha=0.3)
    out = run / f"{prefix}queue_depth.png"
    fig.tight_layout()
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"[ok ] wrote {out}")

    # Seed retention
    if seeds:
        queue_seeds = set(df["seedName"].dropna().astype(str))
        retained = sorted(queue_seeds & seeds)
        missing = sorted(seeds - queue_seeds)
        data = {
            "total_initial_seeds": len(seeds),
            "seeds_in_queue": len(queue_seeds),
            "initial_seeds_retained": len(retained),
            "initial_seeds_missing": len(missing),
        }
        out_json = run / f"{prefix}seed_retention.json"
        out_json.write_text(json.dumps({"retained": retained, "missing": missing, **data}, indent=2))
        print(f"[ok ] wrote {out_json}")


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Plot C2Fuzz run metrics.")
    ap.add_argument(
        "--run-dir",
        required=True,
        help="Run directory with signals.csv, or a root containing multiple runs.",
    )
    ap.add_argument(
        "--seeds-dir",
        help="Optional seeds directory; if provided, seed retention in the queue is reported.",
    )
    ap.add_argument(
        "--output-prefix",
        default="plot_",
        help="Filename prefix for generated plots (default: plot_).",
    )
    return ap.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(args.run_dir).resolve()
    seeds_dir = Path(args.seeds_dir).resolve() if args.seeds_dir else None
    seeds = load_seed_names(seeds_dir) if seeds_dir else set()
    runs = discover_runs(root)
    if not runs:
        raise SystemExit(f"No runs found under {root}")

    for run in runs:
        plot_signals(run, args.output_prefix)
        plot_queue(run, args.output_prefix, seeds)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
