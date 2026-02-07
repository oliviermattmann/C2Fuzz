#!/usr/bin/env python3
"""
Analyze mutation queue monopolies from mutation_queue_snapshot.csv.

For each run, groups queue entries by seedName and reports:
- top seed share of queue entries
- top seed share of timesSelected
- top vs rest average score/priority
- number of distinct seedNames (lineages) present

Summaries are averages across runs.
"""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path
from statistics import mean
from typing import Dict, List, Optional, Tuple


def parse_run_dir_name(run_dir: Path) -> Optional[Dict[str, str]]:
    parts = run_dir.name.split("__")
    if len(parts) < 5:
        return None
    return {
        "timestamp": parts[0],
        "scoring": parts[1],
        "mutator": parts[2],
        "corpus": parts[3],
    }


def collect_run_dirs(root: Path) -> List[Path]:
    return [d for d in root.glob("runs_*_server*/out_*/*__*__*__*__run*") if d.is_dir()]


def parse_float(x: Optional[str]) -> Optional[float]:
    try:
        return float(x) if x is not None and x != "" else None
    except Exception:
        return None


def parse_int(x: Optional[str]) -> Optional[int]:
    try:
        return int(x) if x is not None and x != "" else None
    except Exception:
        return None


def mean_of(values: List[Optional[float]]) -> Optional[float]:
    vals = [v for v in values if v is not None]
    return mean(vals) if vals else None


def collect_runs(root: Path) -> List[Dict]:
    runs: List[Dict] = []
    for d in collect_run_dirs(root):
        meta = parse_run_dir_name(d)
        if not meta:
            continue
        snap = d / "mutation_queue_snapshot.csv"
        if not snap.is_file():
            continue
        seed_rows: Dict[str, List[Dict[str, str]]] = defaultdict(list)
        with snap.open() as f:
            for row in csv.DictReader(f):
                seed = row.get("seedName") or ""
                seed_rows[seed].append(row)
        if not seed_rows:
            continue
        total_rows = sum(len(v) for v in seed_rows.values())

        seed_stats = []
        total_times_selected = 0
        for seed, rows in seed_rows.items():
            prios = [parse_float(r.get("priority")) for r in rows]
            scores = [parse_float(r.get("score")) for r in rows]
            times = [parse_int(r.get("timesSelected")) for r in rows]
            active = [r.get("activeChampion", "").lower() == "true" for r in rows]
            ts_sum = sum(t for t in times if t is not None)
            total_times_selected += ts_sum
            seed_stats.append(
                {
                    "seed": seed,
                    "n": len(rows),
                    "share": len(rows) / total_rows,
                    "prio_mean": mean_of(prios),
                    "score_mean": mean_of(scores),
                    "times_selected_sum": ts_sum,
                    "active_share": mean(active) if active else None,
                }
            )

        seed_stats.sort(key=lambda s: s["n"], reverse=True)
        top = seed_stats[0]
        rest = seed_stats[1:]

        runs.append(
            {
                **meta,
                "distinct_seeds": len(seed_stats),
                "top_share": top["share"],
                "top_times_share": (top["times_selected_sum"] / total_times_selected) if total_times_selected else None,
                "top_prio": top["prio_mean"],
                "rest_prio": mean_of([s["prio_mean"] for s in rest]),
                "top_score": top["score_mean"],
                "rest_score": mean_of([s["score_mean"] for s in rest]),
                "top_active": top["active_share"],
                "rest_active": mean_of([s["active_share"] for s in rest]),
            }
        )
    return runs


def summarize(rows: List[Dict]) -> Dict[str, Optional[float]]:
    def m(key: str) -> Optional[float]:
        vals = [r[key] for r in rows if r.get(key) is not None]
        return mean(vals) if vals else None

    return {
        "runs": float(len(rows)),
        "top_share": m("top_share"),
        "top_times_share": m("top_times_share"),
        "top_prio": m("top_prio"),
        "rest_prio": m("rest_prio"),
        "top_score": m("top_score"),
        "rest_score": m("rest_score"),
        "distinct": m("distinct_seeds"),
    }


def fmt(x: Optional[float], nd: int = 3) -> str:
    if x is None:
        return "-"
    if nd == 0:
        return f"{x:.0f}"
    return f"{x:.{nd}f}"


def render_table(header: List[str], rows: List[List[str]]) -> str:
    lines = []
    lines.append("| " + " | ".join(header) + " |")
    lines.append("| " + " | ".join(["---"] * len(header)) + " |")
    for r in rows:
        lines.append("| " + " | ".join(r) + " |")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="Analyze mutation queue monopolies.")
    ap.add_argument("--root", default="evaluation", help="Root containing runs_*_server*")
    ap.add_argument("--output", default=None, help="Optional output markdown file")
    args = ap.parse_args()

    runs = collect_runs(Path(args.root).resolve())
    if not runs:
        raise SystemExit("No runs found with mutation_queue_snapshot.csv")

    # overall + by corpus
    rows1: List[List[str]] = []
    overall = summarize(runs)
    rows1.append([
        "overall",
        fmt(overall["runs"], 0),
        fmt(overall["top_share"] * 100 if overall["top_share"] is not None else None, 1) + "%",
        fmt(overall["top_times_share"] * 100 if overall["top_times_share"] is not None else None, 1) + "%",
        fmt(overall["top_prio"], 1),
        fmt(overall["rest_prio"], 1),
        fmt(overall["top_score"], 1),
        fmt(overall["rest_score"], 1),
        fmt(overall["distinct"], 0),
    ])

    by_corpus: Dict[str, List[Dict]] = defaultdict(list)
    for r in runs:
        by_corpus[r["corpus"]].append(r)
    for corpus in sorted(by_corpus.keys()):
        s = summarize(by_corpus[corpus])
        rows1.append([
            f"corpus={corpus}",
            fmt(s["runs"], 0),
            fmt(s["top_share"] * 100 if s["top_share"] is not None else None, 1) + "%",
            fmt(s["top_times_share"] * 100 if s["top_times_share"] is not None else None, 1) + "%",
            fmt(s["top_prio"], 1),
            fmt(s["rest_prio"], 1),
            fmt(s["top_score"], 1),
            fmt(s["rest_score"], 1),
            fmt(s["distinct"], 0),
        ])

    header1 = [
        "Group",
        "Runs",
        "Top share",
        "Top timesSelected share",
        "Top priority",
        "Rest priority",
        "Top score",
        "Rest score",
        "Distinct seedNames",
    ]

    # by scoring/corpus
    by_sc: Dict[Tuple[str, str], List[Dict]] = defaultdict(list)
    for r in runs:
        by_sc[(r["scoring"], r["corpus"])].append(r)

    rows2: List[List[str]] = []
    for (scoring, corpus) in sorted(by_sc.keys()):
        s = summarize(by_sc[(scoring, corpus)])
        rows2.append([
            scoring,
            corpus,
            fmt(s["runs"], 0),
            fmt(s["top_share"] * 100 if s["top_share"] is not None else None, 1) + "%",
            fmt(s["top_times_share"] * 100 if s["top_times_share"] is not None else None, 1) + "%",
            fmt(s["distinct"], 0),
        ])

    header2 = ["Scoring", "Corpus", "Runs", "Top share", "Top timesSelected share", "Distinct seedNames"]

    out = []
    out.append(render_table(header1, rows1))
    out.append("")
    out.append(render_table(header2, rows2))
    content = "\n".join(out)

    if args.output:
        Path(args.output).write_text(content)
    else:
        print(content)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
