#!/usr/bin/env python3
"""
Generate markdown summary tables from evaluation runs.

- Coverage = "unique edges seen (AFL)" from final_metrics.txt
- Throughput = "throughput (dispatch/s)" from final_metrics.txt
- Bugs = "found bugs" from final_metrics.txt

The script searches for run directories matching:
  <root>/runs_*_server*/out_*/*__*__*__*__run*

Outputs two markdown tables:
  1) Coverage + throughput (avg, sd)
  2) Bugs per run (values joined by '/'; ordered by repetition if available)

Optional:
  3) Queue monopoly summary from mutation_queue_snapshot.csv
"""

from __future__ import annotations

import argparse
import csv
import re
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple


def parse_final_metrics(path: Path) -> Dict[str, Optional[float]]:
    fm = path / "final_metrics.txt"
    if not fm.is_file():
        return {}
    text = fm.read_text(errors="ignore")

    def get_int(pattern: str) -> Optional[int]:
        m = re.search(pattern, text)
        return int(m.group(1).replace(",", "")) if m else None

    def get_float(pattern: str) -> Optional[float]:
        m = re.search(pattern, text)
        return float(m.group(1)) if m else None

    return {
        "bugs": get_int(r"found bugs:\s*([0-9,]+)"),
        "edges": get_int(r"unique edges seen \(AFL\):\s*([0-9,]+)"),
        "tput": get_float(r"throughput \(dispatch/s\):\s*([0-9.]+)"),
    }


def order_with_preferred(values: Iterable[str], preferred: List[str]) -> List[str]:
    vals = list(values)
    ordered = [v for v in preferred if v in vals]
    ordered += sorted(v for v in vals if v not in ordered)
    return ordered


def mean(xs: List[float]) -> Optional[float]:
    return sum(xs) / len(xs) if xs else None


def stdev(xs: List[float]) -> Optional[float]:
    if not xs:
        return None
    if len(xs) == 1:
        return 0.0
    return statistics.stdev(xs)


def fmt_int(x: Optional[float]) -> str:
    return f"{int(round(x)):,}" if x is not None else "-"


def fmt_float(x: Optional[float], nd: int = 2) -> str:
    return f"{x:.{nd}f}" if x is not None else "-"


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


def collect_runs(root: Path) -> List[Dict]:
    runs: List[Dict] = []
    for d in collect_run_dirs(root):
        meta = parse_run_dir_name(d)
        if not meta:
            continue
        rep_match = re.search(r"runs_(\d+)_server\d+", str(d))
        rep = int(rep_match.group(1)) if rep_match else None
        metrics = parse_final_metrics(d)
        if not metrics:
            continue
        runs.append(
            {
                "path": d,
                "rep": rep,
                **meta,
                **metrics,
            }
        )
    return runs


def parse_queue_snapshot(path: Path) -> Optional[Dict[str, float]]:
    snap = path / "mutation_queue_snapshot.csv"
    if not snap.is_file():
        return None
    seed_counts: Dict[str, int] = defaultdict(int)
    total = 0
    with snap.open() as f:
        reader = csv.DictReader(f)
        for row in reader:
            seed = row.get("seedName") or ""
            seed_counts[seed] += 1
            total += 1
    if total == 0:
        return None
    top_seed, top_count = max(seed_counts.items(), key=lambda kv: kv[1])
    return {
        "queue_n": total,
        "top_seed": top_seed,
        "top_seed_count": top_count,
        "top_seed_share": top_count / total,
        "distinct_seeds": float(len(seed_counts)),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Summarize evaluation runs as markdown tables.")
    ap.add_argument(
        "--root",
        default="evaluation",
        help="Root directory containing runs_*_server* (default: evaluation)",
    )
    ap.add_argument(
        "--output",
        default=None,
        help="Optional output file (markdown). If omitted, prints to stdout.",
    )
    ap.add_argument(
        "--repeat-scoring",
        action="store_true",
        help="Repeat scoring mode in every row (default: blank on 2nd row).",
    )
    ap.add_argument(
        "--queue-summary",
        action="store_true",
        help="Append queue monopoly summary from mutation_queue_snapshot.csv.",
    )
    args = ap.parse_args()

    root = Path(args.root).resolve()
    runs = collect_runs(root)
    if not runs:
        raise SystemExit(f"No runs found under {root}")

    # Group by config
    agg: Dict[Tuple[str, str, str], List[Dict]] = defaultdict(list)
    for r in runs:
        agg[(r["scoring"], r["corpus"], r["mutator"])].append(r)

    scorings = sorted({r["scoring"] for r in runs})
    corpora = order_with_preferred({r["corpus"] for r in runs}, ["champion", "random"])
    mutators = order_with_preferred({r["mutator"] for r in runs}, ["bandit", "mop", "uniform"])

    # Determine repetition ordering for bugs
    rep_set = sorted({r["rep"] for r in runs if r["rep"] is not None})
    if rep_set:
        bugs_header = f"bugs (runs{'/'.join(str(r) for r in rep_set)})"
    else:
        bugs_header = "bugs (runs)"

    # Table 1: coverage + throughput
    header1 = ["Scoring mode", "Corpus mgmt"]
    header1 += [f"{m} cov (avg,sd)" for m in mutators]
    header1 += [f"{m} tput (avg,sd)" for m in mutators]

    rows1: List[List[str]] = []
    for scoring in scorings:
        first = True
        for corpus in corpora:
            row = [scoring if (first or args.repeat_scoring) else "", corpus]
            first = False
            # coverage
            for mut in mutators:
                vals = agg.get((scoring, corpus, mut), [])
                edges = [v["edges"] for v in vals if v["edges"] is not None]
                row.append(f"{fmt_int(mean(edges))} ({fmt_int(stdev(edges))})")
            # throughput
            for mut in mutators:
                vals = agg.get((scoring, corpus, mut), [])
                tputs = [v["tput"] for v in vals if v["tput"] is not None]
                row.append(f"{fmt_float(mean(tputs),2)} ({fmt_float(stdev(tputs),2)})")
            rows1.append(row)

    # Table 2: bugs
    header2 = ["Scoring mode", "Corpus mgmt"]
    header2 += [f"{m} {bugs_header}" for m in mutators]

    rows2: List[List[str]] = []
    for scoring in scorings:
        first = True
        for corpus in corpora:
            row = [scoring if (first or args.repeat_scoring) else "", corpus]
            first = False
            for mut in mutators:
                vals = agg.get((scoring, corpus, mut), [])
                if rep_set:
                    by_rep = {v["rep"]: v["bugs"] for v in vals}
                    bugs = [by_rep.get(r, "-") for r in rep_set]
                else:
                    vals_sorted = sorted(vals, key=lambda v: v["timestamp"])
                    bugs = [v["bugs"] if v["bugs"] is not None else "-" for v in vals_sorted]
                row.append("/".join(str(b) for b in bugs) if bugs else "-")
            rows2.append(row)

    def render_table(header: List[str], rows: List[List[str]]) -> str:
        lines = []
        lines.append("| " + " | ".join(header) + " |")
        lines.append("| " + " | ".join(["---"] * len(header)) + " |")
        for r in rows:
            lines.append("| " + " | ".join(r) + " |")
        return "\n".join(lines)

    out = []
    out.append(render_table(header1, rows1))
    out.append("")
    out.append(render_table(header2, rows2))

    if args.queue_summary:
        queue_runs: List[Dict] = []
        for d in collect_run_dirs(root):
            meta = parse_run_dir_name(d)
            if not meta:
                continue
            stats = parse_queue_snapshot(d)
            if not stats:
                continue
            queue_runs.append({**meta, **stats})

        if queue_runs:
            q_agg: Dict[Tuple[str, str, str], List[Dict]] = defaultdict(list)
            for r in queue_runs:
                q_agg[(r["scoring"], r["corpus"], r["mutator"])].append(r)

            q_scorings = sorted({k[0] for k in q_agg})
            q_corpora = order_with_preferred({k[1] for k in q_agg}, ["champion", "random"])
            q_mutators = order_with_preferred({k[2] for k in q_agg}, ["bandit", "mop", "uniform"])

            header3 = ["Scoring mode", "Corpus mgmt", "Mutator", "Top seed share", "Distinct seedNames"]
            rows3: List[List[str]] = []
            for scoring in q_scorings:
                first = True
                for corpus in q_corpora:
                    for mut in q_mutators:
                        vals = q_agg.get((scoring, corpus, mut))
                        if not vals:
                            continue
                        shares = [v["top_seed_share"] for v in vals]
                        distincts = [v["distinct_seeds"] for v in vals]
                        share_text = f"{mean(shares) * 100:.1f}%"
                        distinct_text = f"{mean(distincts):.0f}"
                        if len(vals) > 1:
                            share_text = f"{mean(shares) * 100:.1f}% ({stdev(shares) * 100:.1f}%)"
                            distinct_text = f"{mean(distincts):.0f} ({stdev(distincts):.0f})"
                        row = [scoring if (first or args.repeat_scoring) else "", corpus, mut, share_text, distinct_text]
                        first = False
                        rows3.append(row)

            out.append("")
            out.append(render_table(header3, rows3))

    content = "\n".join(out)

    if args.output:
        Path(args.output).write_text(content)
    else:
        print(content)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
