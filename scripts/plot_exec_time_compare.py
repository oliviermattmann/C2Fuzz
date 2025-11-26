#!/usr/bin/env python3
"""
Plot average execution time over elapsed time for multiple C2Fuzz runs.
Can overlay everything on one plot or facet by scheduler/corpus.

Usage (single plot):
  python3 scripts/plot_exec_time_compare.py \
      --root sweep120/fuzz_sessions/exp_full120_ipw \
      --output plots_exec_time.png

Usage (facet by mutator + corpus):
  python3 scripts/plot_exec_time_compare.py \
      --root sweep120/fuzz_sessions \
      --group-by mutator_corpus \
      --output plots_exec_time_faceted.png

If --root points at a directory with a manifest.json, the script uses the
run metadata (scoring/mutator/corpus) for labels. Otherwise it will recurse
and plot every subdirectory that contains a signals.csv.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd


def load_manifest_runs(manifest_path: Path) -> List[Dict[str, str]]:
    data = json.loads(manifest_path.read_text())
    runs: List[Dict[str, str]] = []
    for run in data.get("runs", []):
        dest = Path(run.get("dest_dir", ""))
        if not dest:
            continue
        if not dest.exists():
            # Fallback: place under manifest parent if dest was absolute from another host
            maybe = manifest_path.parent / dest.name
            if maybe.exists():
                dest = maybe
        label = run.get(
            "label",
            f"{run.get('scoring_mode', '')}__{run.get('mutator_policy', '')}__{run.get('corpus_policy', '')}",
        )
        runs.append(
            {
                "label": label,
                "scoring_mode": run.get("scoring_mode"),
                "mutator_policy": run.get("mutator_policy"),
                "corpus_policy": run.get("corpus_policy"),
                "path": str(dest),
            }
        )
    return runs


def discover_runs(root: Path) -> List[Dict[str, str]]:
    manifest = root / "manifest.json"
    if manifest.is_file():
        return load_manifest_runs(manifest)

    def parse_from_dirname(name: str) -> Dict[str, str]:
        # Expected patterns like: 001_interaction_pair_weighted__uniform__champion__run01
        parts = name.split("__")
        scoring = mutator = corpus = None
        if len(parts) >= 3:
            # First part may have numeric prefix; strip leading digits/underscore.
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

    runs: List[Dict[str, str]] = []
    for sig in root.rglob("signals.csv"):
        run_dir = sig.parent
        meta = parse_from_dirname(run_dir.name)
        runs.append({"path": str(run_dir), **meta})
    return sorted(runs, key=lambda r: r["label"])


def make_label(run: Dict[str, str], prefer_field: Optional[str] = None) -> str:
    def field(key: str) -> Optional[str]:
        return run.get(key) or None

    if prefer_field:
        if prefer_field == "mutator_corpus":
            m = field("mutator_policy") or "unknown"
            c = field("corpus_policy") or "unknown"
            return f"{m.lower()}|{c.lower()}"
        if prefer_field == "scoring_corpus":
            s = field("scoring_mode") or "unknown"
            c = field("corpus_policy") or "unknown"
            return f"{s.lower()}|{c.lower()}"
        if prefer_field == "scoring_mutator":
            s = field("scoring_mode") or "unknown"
            m = field("mutator_policy") or "unknown"
            return f"{s.lower()}|{m.lower()}"
        if field(prefer_field):
            return field(prefer_field).lower()

    parts = []
    if field("scoring_mode"):
        parts.append(field("scoring_mode").lower())
    if field("mutator_policy"):
        parts.append(field("mutator_policy").lower())
    if field("corpus_policy"):
        parts.append(field("corpus_policy").lower())
    if parts:
        return "|".join(parts)
    return run.get("label", "run")


def slugify(label: str) -> str:
    return re.sub(r"[^a-zA-Z0-9]+", "_", label.strip()).strip("_").lower()


def plot_exec_time(
    runs: List[Dict[str, str]],
    output: Path,
    title: Optional[str],
    label_field: Optional[str] = None,
) -> None:
    plt.figure(figsize=(10, 6))
    plotted = 0
    for run in runs:
        sig_path = Path(run["path"]) / "signals.csv"
        if not sig_path.is_file():
            print(f"[skip] {sig_path} missing")
            continue
        df = pd.read_csv(sig_path)
        if df.empty or "avg_exec_runtime_ms" not in df or "elapsed_seconds" not in df:
            print(f"[skip] {sig_path} missing required columns")
            continue
        hours = df["elapsed_seconds"] / 3600.0
        label = make_label(run, prefer_field=label_field)
        plt.plot(hours, df["avg_exec_runtime_ms"], label=label)
        plotted += 1

    if plotted == 0:
        raise SystemExit("No runs plotted (signals.csv missing or empty).")

    plt.xlabel("Elapsed time (hours)")
    plt.ylabel("Avg exec runtime (ms)")
    if title:
        plt.title(title)
    plt.grid(True, alpha=0.3)
    plt.legend(title="config", fontsize=8, bbox_to_anchor=(1.04, 1), loc="upper left")
    plt.tight_layout()
    output.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output, dpi=150)
    plt.close()
    print(f"[ok ] wrote {output}")


def plot_grouped(
    grouped: Sequence[Tuple[str, List[Dict[str, str]]]],
    output: Path,
    title: Optional[str],
    label_field: Optional[str] = None,
) -> None:
    cols = min(3, len(grouped))
    rows = math.ceil(len(grouped) / cols)
    fig, axes = plt.subplots(rows, cols, figsize=(4 * cols + 2, 3 * rows + 1), squeeze=False)
    any_plotted = False

    for idx, (group_label, runs) in enumerate(grouped):
        ax = axes[idx // cols][idx % cols]
        plotted = 0
        for run in runs:
            sig_path = Path(run["path"]) / "signals.csv"
            if not sig_path.is_file():
                print(f"[skip] {sig_path} missing")
                continue
            df = pd.read_csv(sig_path)
            if df.empty or "avg_exec_runtime_ms" not in df or "elapsed_seconds" not in df:
                print(f"[skip] {sig_path} missing required columns")
                continue
            hours = df["elapsed_seconds"] / 3600.0
            label = make_label(run, prefer_field=label_field)
            ax.plot(hours, df["avg_exec_runtime_ms"], label=label)
            plotted += 1

        ax.set_title(group_label)
        ax.set_xlabel("hours")
        ax.set_ylabel("avg exec ms")
        ax.grid(True, alpha=0.3)
        if plotted:
            ax.legend(fontsize=7)
            any_plotted = True
        else:
            ax.text(0.5, 0.5, "no data", ha="center", va="center")

    # Hide unused axes if any
    for j in range(idx + 1, rows * cols):
        ax = axes[j // cols][j % cols]
        ax.axis("off")

    if not any_plotted:
        raise SystemExit("No runs plotted (signals.csv missing or empty).")

    if title:
        fig.suptitle(title)
    fig.tight_layout()
    output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output, dpi=150)
    plt.close(fig)
    print(f"[ok ] wrote {output}")


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(
        description="Overlay average execution time for multiple C2Fuzz runs."
    )
    ap.add_argument(
        "--root",
        required=True,
        help="Run directory (with signals.csv) or a root containing multiple runs/manifest.json.",
    )
    ap.add_argument(
        "--output",
        default="plots_exec_time.png",
        help="Path to the output PNG file.",
    )
    ap.add_argument(
        "--title",
        help="Optional plot title.",
    )
    ap.add_argument(
        "--group-by",
        choices=["mutator_corpus", "mutator", "corpus", "scoring"],
        help="Facet plots by the chosen key. mutator_corpus creates one subplot per (scheduler, corpus) combo. scoring creates one subplot per scoring mode.",
    )
    ap.add_argument(
        "--split-outputs",
        action="store_true",
        help="When grouping, emit one PNG per group (named with a slug). Labels default to scoring mode within each group.",
    )
    ap.add_argument(
        "--label-field",
        choices=[
            "scoring_mode",
            "mutator_policy",
            "corpus_policy",
            "mutator_corpus",
            "scoring_corpus",
            "scoring_mutator",
        ],
        help="Override the label used for lines within each plot. Default depends on grouping.",
    )
    return ap.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(args.root).resolve()
    if not root.exists():
        raise SystemExit(f"Root not found: {root}")
    runs = discover_runs(root)
    if not runs:
        raise SystemExit(f"No runs discovered under {root}")
    group_by = args.group_by

    def group_runs(runs: List[Dict[str, str]]):
        if not group_by:
            return [("all runs", runs)]
        buckets: Dict[str, List[Dict[str, str]]] = {}
        for run in runs:
            if group_by == "mutator_corpus":
                key = (
                    (run.get("mutator_policy") or "unknown").lower(),
                    (run.get("corpus_policy") or "unknown").lower(),
                )
                label = f"{key[0]} | {key[1]}"
            elif group_by == "mutator":
                key = (run.get("mutator_policy") or "unknown").lower()
                label = key
            elif group_by == "corpus":
                key = (run.get("corpus_policy") or "unknown").lower()
                label = key
            else:  # scoring
                key = (run.get("scoring_mode") or "unknown").lower()
                label = key
            buckets.setdefault(label, []).append(run)
        return sorted(buckets.items(), key=lambda kv: kv[0])

    grouped = group_runs(runs)
    if group_by:
        out_path = Path(args.output)
        if args.split_outputs:
            base = out_path
            for group_label, group_runs in grouped:
                suffix = slugify(group_label) or "group"
                group_out = base.with_name(f"{base.stem}_{suffix}{base.suffix}")
                default_label = "scoring_corpus" if group_by == "mutator" else (
                    "scoring_mutator" if group_by == "corpus" else
                    ("mutator_corpus" if group_by == "scoring" else "scoring_mode")
                )
                plot_exec_time(
                    group_runs,
                    group_out,
                    group_label,
                    label_field=args.label_field or default_label,
                )
        else:
            default_label = "scoring_corpus" if group_by == "mutator" else (
                "scoring_mutator" if group_by == "corpus" else
                ("mutator_corpus" if group_by == "scoring" else "scoring_mode")
            )
            plot_grouped(
                grouped,
                Path(args.output),
                args.title,
                label_field=args.label_field or default_label,
            )
    else:
        plot_exec_time(runs, Path(args.output), args.title)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
