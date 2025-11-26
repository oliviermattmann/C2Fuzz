#!/usr/bin/env python3
"""
Visualize which mutators are favored under each mutator policy, using the 2h sweep runs.

For each mutator policy (UNIFORM, MOP, BANDIT), this script reads
mutator_optimization_stats.csv from each run, takes the latest row per mutator,
and aggregates mutation attempts (success + skip + failure + compile_failures).
It then emits one bar chart per policy showing total attempts per mutator.

Usage:
  python3 scripts/plot_mutator_favored.py --root sweep120/fuzz_sessions --output-prefix favored
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path
from typing import Dict, List

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402


def collect_runs(root: Path) -> List[Dict[str, str]]:
    runs: List[Dict[str, str]] = []
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
                    "mutator_policy": run.get("mutator_policy", "").upper(),
                    "path": dest,
                    "label": run.get("label", dest.name),
                }
            )
    else:
        for stats in root.rglob("mutator_optimization_stats.csv"):
            run_dir = stats.parent
            parts = run_dir.name.split("__")
            mutator_policy = parts[1].upper() if len(parts) >= 3 else ""
            runs.append({"mutator_policy": mutator_policy, "path": run_dir, "label": run_dir.name})
    return runs


def latest_by_mutator(stats_path: Path) -> Dict[str, Dict[str, float]]:
    latest: Dict[str, Dict[str, float]] = {}
    with stats_path.open() as f:
        reader = csv.DictReader(f)
        for row in reader:
            mut = row.get("mutator")
            if not mut:
                continue
            latest[mut] = row
    return latest


def counts_from_row(row: Dict[str, str]) -> Dict[str, float]:
    def num(key: str) -> float:
        try:
            return float(row.get(key, "0") or 0.0)
        except ValueError:
            return 0.0

    return {
        "success": num("mutation_success"),
        "skip": num("mutation_skip"),
        "failure": num("mutation_failure"),
        "compile": num("compile_failures"),
    }


def plot_policy(policy: str, counts: Dict[str, Dict[str, float]], out: Path) -> None:
    if not counts:
        return
    # order by total attempts
    items = []
    for mut, parts in counts.items():
        total = sum(parts.values())
        items.append((mut, total, parts))
    items.sort(key=lambda kv: kv[1], reverse=True)
    muts = [k for k, _, _ in items]
    succ = [parts["success"] for _, _, parts in items]
    skip = [parts["skip"] for _, _, parts in items]
    fail = [parts["failure"] for _, _, parts in items]
    comp = [parts["compile"] for _, _, parts in items]

    x = range(len(muts))
    plt.figure(figsize=(max(6, len(items) * 0.45), 4.5))
    p1 = plt.bar(x, succ, color="#4C78A8", label="success")
    p2 = plt.bar(x, skip, bottom=succ, color="#F28E2B", label="skip")
    bottom_fail = [s + sk for s, sk in zip(succ, skip)]
    p3 = plt.bar(x, fail, bottom=bottom_fail, color="#E15759", label="failure")
    bottom_comp = [b + f for b, f in zip(bottom_fail, fail)]
    p4 = plt.bar(x, comp, bottom=bottom_comp, color="#59A14F", label="compile")
    plt.xticks(x, muts, rotation=60, ha="right", fontsize=8)
    plt.ylabel("Mutation attempts")
    plt.title(f"Mutator preference | policy={policy}")
    plt.legend(fontsize=8)
    plt.tight_layout()
    out.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(out, dpi=150)
    plt.close()
    print(f"[ok] wrote {out}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Plot mutator favoring by mutator policy.")
    ap.add_argument("--root", required=True, help="Root directory with sweep runs.")
    ap.add_argument("--output-prefix", default="favored", help="Output prefix for PNGs.")
    args = ap.parse_args()

    runs = collect_runs(Path(args.root).resolve())
    if not runs:
        raise SystemExit("No runs found.")

    agg_by_policy: Dict[str, Dict[str, Dict[str, float]]] = defaultdict(lambda: defaultdict(lambda: defaultdict(float)))
    for run in runs:
        stats_path = Path(run["path"]) / "mutator_optimization_stats.csv"
        if not stats_path.is_file():
            continue
        latest = latest_by_mutator(stats_path)
        for mut, row in latest.items():
            parts = counts_from_row(row)
            for k, v in parts.items():
                agg_by_policy[run["mutator_policy"]][mut][k] += v

    for policy, counts in agg_by_policy.items():
        out = Path(f"{args.output_prefix}_{policy.lower()}.png")
        plot_policy(policy, counts, out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
