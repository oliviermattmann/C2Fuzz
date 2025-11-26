#!/usr/bin/env python3
"""
Render coverage/runtime summaries as PNG tables.

Outputs:
  - <prefix>_averages.png : averages per scoring mode
  - <prefix>_per_run.png  : per-run rows grouped by scoring mode

Usage:
  python3 scripts/plot_summary_tables.py --root sweep120/fuzz_sessions --output-prefix summary_tables
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Dict, List

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402


def collect_runs(root: Path) -> List[Dict]:
    def short_label(label: str) -> str:
        parts = label.split("__")
        return parts[-1] if len(parts) > 1 else label

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
                    "label": short_label(run.get("label", dest.name)),
                    "path": dest,
                }
            )
    else:
        for sig in root.rglob("signals.csv"):
            run_dir = sig.parent
            parts = run_dir.name.split("__")
            scoring = parts[0].split("_", 1)[-1] if len(parts) >= 3 else ""
            mutator = parts[1] if len(parts) >= 3 else ""
            corpus = parts[2] if len(parts) >= 3 else ""
            runs.append({"scoring": scoring, "mutator": mutator, "corpus": corpus, "label": short_label(run_dir.name), "path": run_dir})
    return runs


def load_final(path: Path) -> Dict[str, float]:
    sig = path / "signals.csv"
    if not sig.is_file():
        return {}
    rows = list(csv.DictReader(sig.open()))
    if not rows:
        return {}
    last = rows[-1]
    def num(k):
        try:
            return float(last.get(k, "") or 0.0)
        except ValueError:
            return 0.0
    return {
        "pairs": num("unique_pairs"),
        "total_pairs": num("total_pairs"),
        "features": num("unique_features"),
        "total_features": num("total_features"),
        "avg_exec_ms": num("avg_exec_runtime_ms"),
        "tests": num("total_tests"),
    }


def draw_table(headers: List[str], rows: List[List[str]], title: str, out: Path, col_widths: List[float] | None = None) -> None:
    if col_widths and len(col_widths) == len(headers):
        fig_width = sum(col_widths) * 10  # scale for visibility
    else:
        fig_width = len(headers) * 1.6
        col_widths = None
    fig, ax = plt.subplots(figsize=(fig_width, 0.4 * len(rows) + 1.2))
    ax.axis("off")
    table = ax.table(cellText=rows, colLabels=headers, loc="center", colWidths=col_widths)
    table.auto_set_font_size(False)
    table.set_fontsize(8)
    table.scale(1, 1.2)
    ax.set_title(title, pad=4)
    fig.subplots_adjust(top=0.9, bottom=0.05, left=0.01, right=0.99)
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=200)
    plt.close(fig)
    print(f"[ok] wrote {out}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Render summary tables as PNG.")
    ap.add_argument("--root", required=True, help="Root with runs (manifest + signals.csv).")
    ap.add_argument("--output-prefix", default="summary_tables", help="Output prefix for PNG files.")
    args = ap.parse_args()

    runs = collect_runs(Path(args.root).resolve())
    if not runs:
        raise SystemExit("No runs found.")

    records = []
    for r in runs:
        metrics = load_final(r["path"])
        if not metrics:
            continue
        records.append({**r, **metrics})

    # Averages per scoring
    from collections import defaultdict
    agg = defaultdict(list)
    for rec in records:
        agg[rec["scoring"]].append(rec)
    avg_rows = []
    for scoring in sorted(agg.keys()):
        rows = agg[scoring]
        n = len(rows)
        pairs = sum(r["pairs"] for r in rows) / n
        t_pairs = sum(r["total_pairs"] for r in rows) / n
        feats = sum(r["features"] for r in rows) / n
        t_feats = sum(r["total_features"] for r in rows) / n
        avg_exec = sum(r["avg_exec_ms"] for r in rows) / n
        tests = sum(r["tests"] for r in rows) / n
        avg_rows.append([
            scoring,
            f"{pairs:.1f}/{t_pairs:.0f}",
            f"{feats:.1f}/{t_feats:.0f}",
            f"{avg_exec:.1f}",
            f"{tests:.0f}",
            f"n={n}",
        ])
    draw_table(
        ["Scoring", "Pairs", "Features", "Avg exec ms", "Tests", ""],
        avg_rows,
        "Averages per scoring mode (final signals row)",
        Path(f"{args.output_prefix}_averages.png"),
        col_widths=[0.25, 0.18, 0.18, 0.18, 0.12, 0.09],
    )

    # Per run grouped by scoring
    per_rows = []
    for scoring in sorted(agg.keys()):
        per_rows.append([f"{scoring}", "", "", "", "", ""])
        for r in sorted(agg[scoring], key=lambda x: (x["mutator"], x["corpus"])):
            per_rows.append([
                f"{r['mutator']}/{r['corpus']}",
                f"{r['pairs']:.0f}/{r['total_pairs']:.0f}",
                f"{r['features']:.0f}/{r['total_features']:.0f}",
                f"{r['avg_exec_ms']:.1f}",
                f"{int(r['tests'])}",
                r["label"],
            ])
    draw_table(
            ["Scoring or mut/corp", "Pairs", "Features", "Avg exec ms", "Tests", "Run"],
            per_rows,
            "Per-run summary (final signals row)",
            Path(f"{args.output_prefix}_per_run.png"),
            col_widths=[0.22, 0.16, 0.16, 0.14, 0.12, 0.30],
        )

    # Per scoring separate tables
    for scoring in sorted(agg.keys()):
        rows = []
        for r in sorted(agg[scoring], key=lambda x: (x["mutator"], x["corpus"])):
            rows.append([
                f"{r['mutator']}/{r['corpus']}",
                f"{r['pairs']:.0f}/{r['total_pairs']:.0f}",
                f"{r['features']:.0f}/{r['total_features']:.0f}",
                f"{r['avg_exec_ms']:.1f}",
                f"{int(r['tests'])}",
                r["label"],
            ])
        draw_table(
            ["Mut/Corp", "Pairs", "Features", "Avg exec ms", "Tests", "Run"],
            rows,
            f"Per-run summary | scoring={scoring}",
            Path(f"{args.output_prefix}_per_run_{scoring}.png"),
            col_widths=[0.22, 0.16, 0.16, 0.14, 0.12, 0.30],
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
