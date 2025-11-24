#!/usr/bin/env python3
"""Analyze mutation_queue_snapshot.csv files."""
import argparse
import csv
import math
from collections import Counter, defaultdict
from statistics import mean

BUCKETS = [
    (0, 0),
    (1, 2),
    (3, 5),
    (6, 10),
    (11, math.inf),
]


def bucket_label(low, high):
    if math.isinf(high):
        return f">={low}"
    if low == high:
        return str(low)
    return f"{low}-{high}"


def parse_float(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def parse_int(value, default=0):
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def analyze(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            rows.append(row)

    if not rows:
        print("No rows found in", path)
        return

    total = len(rows)
    print(f"Total queued testcases: {total}")

    seeds = Counter(row["seedName"] for row in rows)
    print(f"Unique seeds: {len(seeds)}")

    champions = sum(1 for row in rows if row.get("activeChampion", "").lower() == "true")
    print(f"Active champions: {champions} ({champions/total:.1%})")

    mutators = Counter(row["mutator"] for row in rows)
    print("\nTop mutators by queue share:")
    for mutator, count in mutators.most_common(10):
        print(f"  {mutator:>25} : {count:4d} ({count/total:.1%})")

    print("\nTop seeds by queue share:")
    for seed, count in seeds.most_common(10):
        depths = [parse_int(row["mutationDepth"]) for row in rows if row["seedName"] == seed]
        scores = [parse_float(row["score"]) for row in rows if row["seedName"] == seed]
        avg_depth = mean(depths) if depths else 0.0
        avg_score = mean(scores) if scores else 0.0
        print(f"  {seed:>30} : {count:4d} ({count/total:.1%}) | avg depth {avg_depth:.1f} | avg score {avg_score:.3f}")

    depth_hist = Counter()
    for row in rows:
        depth = parse_int(row["mutationDepth"])
        for low, high in BUCKETS:
            if low <= depth <= high:
                depth_hist[bucket_label(low, high)] += 1
                break

    print("\nMutation depth distribution:")
    for low, high in BUCKETS:
        label = bucket_label(low, high)
        count = depth_hist.get(label, 0)
        print(f"  {label:>5}: {count:4d} ({count/total:.1%})")

    per_seed_depth = {}
    for seed in seeds:
        depths = [parse_int(row["mutationDepth"]) for row in rows if row["seedName"] == seed]
        per_seed_depth[seed] = (min(depths), max(depths))

    print("\nSeed depth min/max (top 5 widest ranges):")
    widest = sorted(seeds.keys(), key=lambda s: per_seed_depth[s][1] - per_seed_depth[s][0], reverse=True)[:5]
    for seed in widest:
        min_d, max_d = per_seed_depth[seed]
        print(f"  {seed:>30} : min {min_d:2d} max {max_d:2d} range {max_d - min_d}")

    mutation_counts = Counter(parse_int(row["mutationCount"]) for row in rows)
    print("\nTop mutationCount values:")
    for count_value, freq in mutation_counts.most_common(5):
        print(f"  count {count_value:4d}: {freq:4d} ({freq/total:.1%})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Analyze mutation_queue_snapshot.csv data")
    parser.add_argument("snapshot", help="Path to mutation_queue_snapshot.csv")
    args = parser.parse_args()
    analyze(args.snapshot)
