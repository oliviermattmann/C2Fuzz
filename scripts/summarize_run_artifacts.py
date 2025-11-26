#!/usr/bin/env python3
"""
Summarize a C2Fuzz run directory by inspecting signals.csv,
mutation_queue_snapshot.csv, and mutator_optimization_stats.csv.

Usage:
  python3 scripts/summarize_run_artifacts.py --run-dir /path/to/run
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path
from typing import Dict, List, Tuple, Iterable


def parse_number(raw: str):
    try:
        if "." in raw:
            return float(raw)
        return int(raw)
    except (TypeError, ValueError):
        return raw


def parse_signals(path: Path) -> Dict[str, float]:
    with path.open(newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    if not rows:
        return {}
    first, last = rows[0], rows[-1]
    def num(row, key): return parse_number(row.get(key, "0"))
    return {
        "start_elapsed_s": num(first, "elapsed_seconds"),
        "end_elapsed_s": num(last, "elapsed_seconds"),
        "total_tests": num(last, "total_tests"),
        "scored_tests": num(last, "scored_tests"),
        "failed_compilations": num(last, "failed_compilations"),
        "found_bugs": num(last, "found_bugs"),
        "unique_features": f"{num(last, 'unique_features')}/{num(last, 'total_features')}",
        "unique_pairs": f"{num(last, 'unique_pairs')}/{num(last, 'total_pairs')}",
        "avg_score": num(last, "avg_score"),
        "max_score": num(last, "max_score"),
        "corpus_size": num(last, "corpus_size"),
    }


def parse_queue(path: Path) -> Dict[str, float]:
    with path.open(newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    if not rows:
        return {}
    scores = []
    for row in rows:
        try:
            scores.append(float(row.get("score", 0.0)))
        except ValueError:
            continue
    size = len(rows)
    best = max(scores) if scores else 0.0
    avg = sum(scores) / len(scores) if scores else 0.0
    return {"queue_size": size, "best_score": best, "avg_score": avg}


def parse_mutator_stats(path: Path, top_n: int | None = 5) -> Tuple[List[Dict], Dict[str, int]]:
    agg: Dict[str, Dict[str, float]] = {}
    with path.open(newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            mut = row.get("mutator", "UNKNOWN")
            bucket = agg.setdefault(mut, {"success": 0, "skip": 0, "failure": 0, "compile_failures": 0})
            bucket["success"] += float(row.get("mutation_success", 0) or 0)
            bucket["skip"] += float(row.get("mutation_skip", 0) or 0)
            bucket["failure"] += float(row.get("mutation_failure", 0) or 0)
            bucket["compile_failures"] += float(row.get("compile_failures", 0) or 0)
    top = sorted(
        (
            {"mutator": m, **vals, "total": sum(vals.values())}
            for m, vals in agg.items()
        ),
        key=lambda x: (-x["success"], -x["total"], x["mutator"])
    )
    if top_n is not None:
        top = top[:top_n]
    totals = {
        "mutators_seen": len(agg),
    }
    return top, totals


def main() -> int:
    ap = argparse.ArgumentParser(description="Summarize C2Fuzz run artifacts.")
    ap.add_argument(
        "--run-dir",
        required=True,
        help="Path to a run directory containing signals.csv etc., or a root containing multiple runs.",
    )
    ap.add_argument(
        "--top-mutators",
        type=int,
        default=5,
        help="How many mutators to show (use 0 or negative to show all).",
    )
    ap.add_argument(
        "--output",
        help="Optional path to write a CSV summary of all runs discovered under run-dir.",
    )
    args = ap.parse_args()

    run_dir = Path(args.run_dir).resolve()
    if not run_dir.is_dir():
        raise SystemExit(f"Run directory not found: {run_dir}")

    # Discover runs: if this directory has signals.csv, treat as single run; otherwise recurse
    runs: List[Path] = []
    if (run_dir / "signals.csv").is_file():
        runs = [run_dir]
    else:
        runs = [p.parent for p in run_dir.rglob("signals.csv")]
        runs = sorted(set(runs))
    if not runs:
        raise SystemExit(f"No runs with signals.csv found under {run_dir}")

    summaries: List[Dict[str, object]] = []
    top_n = None if args.top_mutators <= 0 else args.top_mutators

    for rd in runs:
        signals_path = rd / "signals.csv"
        queue_path = rd / "mutation_queue_snapshot.csv"
        mut_path = rd / "mutator_optimization_stats.csv"

        print(f"Run: {rd}")

        sig = parse_signals(signals_path) if signals_path.is_file() else {}
        if sig:
            print("  signals:")
            for k in ["end_elapsed_s", "total_tests", "scored_tests", "failed_compilations", "found_bugs",
                      "unique_features", "unique_pairs", "avg_score", "max_score", "corpus_size"]:
                if k in sig:
                    print(f"    {k}: {sig[k]}")
        else:
            print("  signals.csv: missing or empty")

        qs = parse_queue(queue_path) if queue_path.is_file() else {}
        if qs:
            print("  mutation_queue_snapshot:")
            for k in ["queue_size", "best_score", "avg_score"]:
                if k in qs:
                    print(f"    {k}: {qs[k]}")
        else:
            print("  mutation_queue_snapshot.csv: missing or empty")

        if mut_path.is_file():
            top, totals = parse_mutator_stats(mut_path, top_n=top_n)
            print(f"  mutator_optimization_stats (mutators_seen={totals['mutators_seen']}):")
            for entry in top:
                print(
                    f"    {entry['mutator']}: success={entry['success']:.0f}, "
                    f"skip={entry['skip']:.0f}, failure={entry['failure']:.0f}, "
                    f"compile_failures={entry['compile_failures']:.0f}"
                )
            mutators_seen = totals["mutators_seen"]
            top_mutators_str = "; ".join(
                f"{e['mutator']} s={e['success']:.0f} sk={e['skip']:.0f} f={e['failure']:.0f} cfail={e['compile_failures']:.0f}"
                for e in top
            )
        else:
            print("  mutator_optimization_stats.csv: missing")
            mutators_seen = 0
            top_mutators_str = ""

        summaries.append({
            "run_dir": str(rd),
            "end_elapsed_s": sig.get("end_elapsed_s"),
            "total_tests": sig.get("total_tests"),
            "scored_tests": sig.get("scored_tests"),
            "failed_compilations": sig.get("failed_compilations"),
            "found_bugs": sig.get("found_bugs"),
            "unique_features": sig.get("unique_features"),
            "unique_pairs": sig.get("unique_pairs"),
            "avg_score": sig.get("avg_score"),
            "max_score": sig.get("max_score"),
            "corpus_size": sig.get("corpus_size"),
            "queue_size": qs.get("queue_size"),
            "queue_best_score": qs.get("best_score"),
            "queue_avg_score": qs.get("avg_score"),
            "mutators_seen": mutators_seen,
            "top_mutators": top_mutators_str,
        })

    if args.output:
        out_path = Path(args.output).resolve()
        fieldnames = [
            "run_dir", "end_elapsed_s", "total_tests", "scored_tests", "failed_compilations", "found_bugs",
            "unique_features", "unique_pairs", "avg_score", "max_score", "corpus_size",
            "queue_size", "queue_best_score", "queue_avg_score",
            "mutators_seen", "top_mutators",
        ]
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with out_path.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for row in summaries:
                writer.writerow(row)
        print(f"\nWrote summary for {len(summaries)} run(s) to {out_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
