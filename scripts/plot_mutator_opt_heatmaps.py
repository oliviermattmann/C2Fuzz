#!/usr/bin/env python3
"""
Heatmaps of (mutator -> optimization feature) for mutator_optimization_stats.csv.

Focuses on mutator scheduler bias: filters to mutator_policy=UNIFORM (from manifest
or run directory name), aggregates counts across runs, and produces:
  - One combined heatmap (all scoring modes) for UNIFORM scheduler
  - One heatmap per scoring mode (still UNIFORM scheduler)

Counts are summed across runs in each bucket, then normalized per mutator so rows
represent the fraction of that mutator's optimization events hitting each feature.

Usage:
  python3 scripts/plot_mutator_opt_heatmaps.py \
      --root sweep120/fuzz_sessions \
      --output-prefix heat_uniform \
      --drop-feature IterGVNIteration
"""

from __future__ import annotations

import argparse
import csv
import re
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402


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
            meta = {
                "label": run.get("label", dest.name),
                "scoring_mode": run.get("scoring_mode"),
                "mutator_policy": run.get("mutator_policy"),
                "corpus_policy": run.get("corpus_policy"),
                "path": str(dest),
            }
            runs.append(meta)
    else:
        for sig in root.rglob("mutator_optimization_stats.csv"):
            run_dir = sig.parent
            meta = parse_from_dirname(run_dir.name)
            meta["path"] = str(run_dir)
            runs.append(meta)
    return runs


def feature_columns(header: Iterable[str]) -> List[str]:
    feats = []
    for name in header:
        if name.startswith("OptEvent_") and (name.endswith("_inc") or name.endswith("_dec")):
            base = name[len("OptEvent_") :].rsplit("_", 1)[0]
            feats.append(base)
    return sorted(set(feats))


def aggregate_run(path: Path, drop_features: set[str], suffixes: Tuple[str, ...]) -> Dict[str, Dict[str, float]]:
    by_mut: Dict[str, Dict[str, float]] = defaultdict(lambda: defaultdict(float))
    with path.open(newline="") as f:
        reader = csv.DictReader(f)
        feats = feature_columns(reader.fieldnames or [])
        for row in reader:
            mut = row.get("mutator", "UNKNOWN")
            for feat in feats:
                if drop_features and feat in drop_features:
                    continue
                for suffix in suffixes:
                    key = f"OptEvent_{feat}_{suffix}"
                    try:
                        val = float(row.get(key, "0") or 0.0)
                    except ValueError:
                        val = 0.0
                    by_mut[mut][feat] += val
    return by_mut


def merge_counts(into: Dict[str, Dict[str, float]], other: Dict[str, Dict[str, float]]) -> None:
    for mut, feats in other.items():
        dest = into.setdefault(mut, {})
        for feat, val in feats.items():
            dest[feat] = dest.get(feat, 0.0) + val


def build_matrix(matrix: Dict[str, Dict[str, float]], binary: bool = False) -> Tuple[List[str], List[str], np.ndarray]:
    mutators = sorted(matrix.keys())
    feats = sorted({f for m in matrix.values() for f in m.keys()})
    M = np.zeros((len(mutators), len(feats)))
    for i, mut in enumerate(mutators):
        row = matrix[mut]
        for j, feat in enumerate(feats):
            val = row.get(feat, 0.0)
            M[i, j] = 1.0 if binary and val > 0 else val
    return mutators, feats, M


def normalize_matrix(M: np.ndarray, mode: str) -> np.ndarray:
    if mode == "none":
        return M
    if mode == "row":
        row_sums = M.sum(axis=1, keepdims=True)
        row_sums[row_sums == 0] = 1.0
        return M / row_sums
    if mode == "column":
        col_sums = M.sum(axis=0, keepdims=True)
        col_sums[col_sums == 0] = 1.0
        return M / col_sums
    return M


def plot_heatmap(mutators: List[str], feats: List[str], M: np.ndarray, title: str, out: Path, binary: bool) -> None:
    fig, ax = plt.subplots(figsize=(0.5 * len(feats) + 2, 0.4 * len(mutators) + 2))
    vmax = 1 if binary else (M.max() or 1)
    im = ax.imshow(M, cmap="RdYlGn", aspect="auto", vmin=0, vmax=vmax)
    ax.set_xticks(range(len(feats)))
    ax.set_xticklabels(feats, rotation=90)
    ax.set_yticks(range(len(mutators)))
    ax.set_yticklabels(mutators)
    ax.set_title(title)
    cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    cbar.set_label("fraction of mutator opt events")
    for i in range(len(mutators)):
        for j in range(len(feats)):
            ax.text(j, i, f"{M[i, j]*100:.1f}%", ha="center", va="center", fontsize=7)
    fig.tight_layout()
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=200)
    plt.close(fig)
    print(f"[ok] wrote {out}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Plot mutator vs optimization feature heatmaps (normalized).")
    ap.add_argument("--root", required=True, help="Run directory or root containing multiple runs.")
    ap.add_argument("--output-prefix", default="heat_uniform", help="Prefix for output PNGs.")
    ap.add_argument(
        "--drop-feature",
        action="append",
        default=["IterGVNIteration"],
        help="Feature(s) to drop to avoid domination. Repeat to drop multiple. Use empty string to keep all.",
    )
    ap.add_argument(
        "--norm",
        choices=["row", "column", "none"],
        default="row",
        help="Normalization: row=share of mutator events (default), column=share of feature contributed by each mutator, none=raw (useful with --binary).",
    )
    ap.add_argument(
        "--log-scale",
        action="store_true",
        help="Apply log1p before normalization to dampen large counts.",
    )
    ap.add_argument(
        "--events",
        choices=["both", "inc", "dec"],
        default="both",
        help="Which event directions to include (default: both inc+dec).",
    )
    ap.add_argument(
        "--binary",
        action="store_true",
        help="Use presence/absence (1 if any events, 0 otherwise) instead of counts. Tip: combine with --norm none to keep pure 0/1.",
    )
    ap.add_argument(
        "--split-by-corpus",
        action="store_true",
        help="Also emit heatmaps separated by corpus policy (all scoring modes, plus per scoring within each corpus).",
    )
    args = ap.parse_args()
    drop_features = set()
    for df in args.drop_feature or []:
        if df:
            drop_features.add(df)
    if args.binary and args.norm == "row":
        args.norm = "none"

    runs = [r for r in discover_runs(Path(args.root).resolve()) if (r.get("mutator_policy") or "").lower() == "uniform"]
    if not runs:
        raise SystemExit("No runs with mutator_policy=UNIFORM found.")
    suffixes = {"both": ("inc", "dec"), "inc": ("inc",), "dec": ("dec",)}[args.events]

    # Aggregate all uniform runs
    combined: Dict[str, Dict[str, float]] = {}
    by_scoring: Dict[str, Dict[str, Dict[str, float]]] = defaultdict(dict)
    by_corpus: Dict[str, Dict[str, Dict[str, float]]] = defaultdict(dict)
    by_corpus_scoring: Dict[str, Dict[str, Dict[str, Dict[str, float]]]] = defaultdict(lambda: defaultdict(dict))

    for run in runs:
        stats_path = Path(run["path"]) / "mutator_optimization_stats.csv"
        if not stats_path.is_file():
            print(f"[skip] {run['path']}: mutator_optimization_stats.csv missing")
            continue
        counts = aggregate_run(stats_path, drop_features, suffixes)
        merge_counts(combined, counts)
        scoring = (run.get("scoring_mode") or "unknown").lower()
        merge_counts(by_scoring.setdefault(scoring, {}), counts)
        corpus = (run.get("corpus_policy") or "unknown").lower()
        merge_counts(by_corpus.setdefault(corpus, {}), counts)
        merge_counts(by_corpus_scoring[corpus].setdefault(scoring, {}), counts)

    # Plot combined
    mutators, feats, M = build_matrix(combined, binary=args.binary)
    if args.log_scale:
        M = np.log1p(M)
    M = normalize_matrix(M, args.norm)
    plot_heatmap(mutators, feats, M, f"UNIFORM scheduler (all scoring modes) | norm={args.norm} | events={args.events} | binary={args.binary}", Path(f"{args.output_prefix}_all.png"), args.binary)

    # Plot per scoring mode
    for scoring, matrix in sorted(by_scoring.items()):
        mutators_s, feats_s, Ms = build_matrix(matrix, binary=args.binary)
        if args.log_scale:
            Ms = np.log1p(Ms)
        Ms = normalize_matrix(Ms, args.norm)
        plot_heatmap(mutators_s, feats_s, Ms, f"UNIFORM scheduler | scoring={scoring} | norm={args.norm} | events={args.events} | binary={args.binary}", Path(f"{args.output_prefix}_{scoring}.png"), args.binary)

    if args.split_by_corpus:
        for corpus, matrix in sorted(by_corpus.items()):
            mut_c, feat_c, Mc = build_matrix(matrix, binary=args.binary)
            if args.log_scale:
                Mc = np.log1p(Mc)
            Mc = normalize_matrix(Mc, args.norm)
            plot_heatmap(mut_c, feat_c, Mc, f"UNIFORM scheduler | corpus={corpus} | norm={args.norm} | events={args.events} | binary={args.binary}", Path(f"{args.output_prefix}_corpus_{corpus}.png"), args.binary)
            for scoring, mat in sorted(by_corpus_scoring[corpus].items()):
                mut_cs, feat_cs, Mcs = build_matrix(mat, binary=args.binary)
                if args.log_scale:
                    Mcs = np.log1p(Mcs)
                Mcs = normalize_matrix(Mcs, args.norm)
                plot_heatmap(mut_cs, feat_cs, Mcs, f"UNIFORM scheduler | corpus={corpus} | scoring={scoring} | norm={args.norm} | events={args.events} | binary={args.binary}", Path(f"{args.output_prefix}_corpus_{corpus}_{scoring}.png"), args.binary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
