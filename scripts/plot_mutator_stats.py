#!/usr/bin/env python3
"""
Quick-and-dirty visualization for mutator_optimization_stats.csv snapshots.

Examples
--------
python scripts/plot_mutator_stats.py fuzz_sessions/<session>/mutator_optimization_stats.csv
python scripts/plot_mutator_stats.py fuzz_sessions/.../mutator_optimization_stats.csv \
    --top-features 12 --output-prefix plots/mutator_stats
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
from typing import List, Optional, Sequence, Tuple
import pandas as pd
import seaborn as sns

import matplotlib

if not os.environ.get("DISPLAY") and os.environ.get("MPLBACKEND") is None:
    matplotlib.use("Agg")

import matplotlib.pyplot as plt


OUTCOME_COLUMNS: Tuple[str, ...] = (
    "mutation_success",
    "mutation_skip",
    "mutation_failure",
    "compile_failures",
    "exec_timeouts",
    "evaluation_failures",
    "evaluation_timeouts",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Plot mutator outcome metrics and optimization deltas from "
            "mutator_optimization_stats.csv."
        )
    )
    parser.add_argument(
        "csv",
        type=Path,
        help="Path to mutator_optimization_stats.csv",
    )
    parser.add_argument(
        "--top-features",
        type=int,
        default=10,
        help="Number of optimization features (by total increase) to show in the heatmap.",
    )
    parser.add_argument(
        "--output-prefix",
        type=Path,
        default=None,
        help="If set, save each figure to <prefix>_<name>.png instead of showing.",
    )
    return parser.parse_args()


def load_latest_snapshot(csv_path: Path) -> pd.DataFrame:
    df = pd.read_csv(csv_path)
    if df.empty:
        raise ValueError(f"No data in {csv_path}")
    # Keep the latest snapshot for each mutator.
    df = df.sort_values(["mutator", "total_records", "elapsed_seconds"])
    latest = df.groupby("mutator", as_index=False).tail(1).reset_index(drop=True)
    return latest


def build_outcome_frame(latest: pd.DataFrame) -> pd.DataFrame:
    missing = [col for col in OUTCOME_COLUMNS if col not in latest.columns]
    if missing:
        raise KeyError(f"Missing columns in CSV: {missing}")
    melted = latest.melt(
        id_vars="mutator",
        value_vars=list(OUTCOME_COLUMNS),
        var_name="metric",
        value_name="count",
    )
    melted = melted[melted["count"] > 0]
    return melted


def feature_columns(latest: pd.DataFrame) -> List[str]:
    return [
        col
        for col in latest.columns
        if col.startswith("OptEvent_") and col.endswith("_inc")
    ]


def build_feature_heatmap_data(
    latest: pd.DataFrame, top_k: int
) -> Tuple[pd.DataFrame, Sequence[str], Optional[pd.Series]]:
    columns = feature_columns(latest)
    if not columns:
        raise ValueError("No OptEvent_*_inc columns found in CSV.")

    iter_col = "OptEvent_IterGVNIteration_inc"
    iter_series = None
    if iter_col in columns:
        iter_series = latest.set_index("mutator")[iter_col]
        columns = [c for c in columns if c != iter_col]

    if not columns:
        heatmap = pd.DataFrame(index=latest["mutator"].unique())
        return heatmap, [], iter_series

    feature_totals = latest[columns].sum().sort_values(ascending=False)
    selected_features = feature_totals.head(top_k).index.tolist()
    heatmap = latest.set_index("mutator")[selected_features]
    return heatmap, selected_features, iter_series


def plot_mutator_stats(
    outcomes: pd.DataFrame,
    heatmap: pd.DataFrame,
    feature_names: Sequence[str],
    iter_series: Optional[pd.Series],
) -> dict[str, plt.Figure]:
    sns.set_theme(style="whitegrid")
    figures: dict[str, plt.Figure] = {}

    # Outcome distribution (stacked bar chart)
    fig_out, ax_out = plt.subplots(figsize=(12, 6))
    sns.barplot(
        data=outcomes,
        x="mutator",
        y="count",
        hue="metric",
        dodge=True,
        ax=ax_out,
    )
    ax_out.set_title("Mutation outcomes / failures per mutator")
    ax_out.set_xlabel("Mutator")
    ax_out.set_ylabel("Count")
    ax_out.tick_params(axis="x", rotation=90)
    fig_out.tight_layout()
    figures["outcomes"] = fig_out

    # Non-IterGVN heatmap
    fig_feat, ax_feat = plt.subplots(
        figsize=(max(8, 0.6 * max(1, len(feature_names))), 6)
    )
    if heatmap.shape[1] > 0:
        sns.heatmap(
            heatmap,
            ax=ax_feat,
            cmap="rocket",
            cbar_kws={"label": "Total increases"},
        )
        ax_feat.set_title(
            f"Top {len(feature_names)} non-IterGVN increases per mutator"
        )
        ax_feat.set_xlabel("Optimization feature")
        ax_feat.set_ylabel("Mutator")
    else:
        ax_feat.axis("off")
        ax_feat.text(
            0.5,
            0.5,
            "No non-IterGVN features found.",
            ha="center",
            va="center",
        )
    fig_feat.tight_layout()
    figures["features"] = fig_feat

    if iter_series is not None:
        fig_iter, ax_iter = plt.subplots(figsize=(4, 6))
        iter_df = iter_series.to_frame(name="IterGVNIteration")
        sns.heatmap(
            iter_df,
            ax=ax_iter,
            cmap="rocket",
            cbar_kws={"label": "Total increases"},
        )
        ax_iter.set_title("IterGVN iteration counts")
        ax_iter.set_xlabel("IterGVN")
        ax_iter.set_ylabel("Mutator")
        fig_iter.tight_layout()
        figures["iter_gvn"] = fig_iter

    return figures


def print_summary_table(
    latest: pd.DataFrame, heatmap: pd.DataFrame, iter_series: Optional[pd.Series]
) -> None:
    print("\n=== Mutation outcome counts per mutator ===")
    outcome_table = latest.set_index("mutator")[list(OUTCOME_COLUMNS)]
    print(outcome_table.to_string())

    if heatmap.shape[1] > 0:
        print("\n=== Top non-IterGVN optimizations per mutator ===")
        for mutator in heatmap.index:
            row = heatmap.loc[mutator]
            top = row[row > 0].sort_values(ascending=False).head(5)
            if top.empty:
                continue
            formatted = ", ".join(
                f"{col.replace('OptEvent_', '').replace('_inc', '')}={int(val)}"
                for col, val in top.items()
            )
            print(f"{mutator}: {formatted}")
    else:
        print("\nNo non-IterGVN optimization deltas recorded.")

    if iter_series is not None:
        print("\n=== IterGVN iteration totals per mutator ===")
        iter_table = iter_series.to_frame(name="IterGVNIterations")
        print(iter_table.to_string())


def main() -> None:
    args = parse_args()
    latest = load_latest_snapshot(args.csv)
    outcome_frame = build_outcome_frame(latest)
    heatmap_frame, feature_names, iter_series = build_feature_heatmap_data(
        latest, max(1, args.top_features)
    )
    figures = plot_mutator_stats(outcome_frame, heatmap_frame, feature_names, iter_series)
    print_summary_table(latest, heatmap_frame, iter_series)

    if args.output_prefix:
        args.output_prefix.parent.mkdir(parents=True, exist_ok=True)
        stem = args.output_prefix.stem or "mutator_stats"
        suffix = args.output_prefix.suffix or ".png"
        for name, fig in figures.items():
            output_path = args.output_prefix.with_name(f"{stem}_{name}{suffix}")
            fig.savefig(output_path, dpi=200)
            print(f"Wrote {name} plot to {output_path}")
    else:
        plt.show()


if __name__ == "__main__":
    main()
