#!/usr/bin/env python3
"""
Quick summary bars:
  1) End-of-run coverage by scoring mode (unique_pairs/total_pairs and unique_features/total_features)
  2) Opt-event evenness by corpus (entropy, max-share), optionally per scoring

Usage:
  python3 scripts/plot_summary_bars.py --root sweep120/fuzz_sessions --output-prefix summary
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Tuple

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402


def entropy(counts: Dict[str, float]) -> float:
    total = sum(counts.values())
    if total <= 0:
        return 0.0
    probs = [c / total for c in counts.values() if c > 0]
    if len(probs) <= 1:
        return 0.0
    h = -sum(p * math.log(p) for p in probs)
    return h / math.log(len(probs))


def parse_runs(root: Path) -> List[Dict[str, str]]:
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
                    "scoring": run.get("scoring_mode", "").lower(),
                    "mutator": run.get("mutator_policy", "").lower(),
                    "corpus": run.get("corpus_policy", "").lower(),
                    "path": str(dest),
                }
            )
    else:
        for sig in root.rglob("signals.csv"):
            run_dir = sig.parent
            name = run_dir.name
            parts = name.split("__")
            scoring = parts[0].split("_", 1)[-1].lower() if len(parts) >= 3 else ""
            mutator = parts[1].lower() if len(parts) >= 3 else ""
            corpus = parts[2].lower() if len(parts) >= 3 else ""
            runs.append({"scoring": scoring, "mutator": mutator, "corpus": corpus, "path": str(run_dir)})
    return runs


def load_final_signals(path: Path) -> Tuple[float, float]:
    sig = path / "signals.csv"
    if not sig.is_file():
        return 0.0, 0.0
    with sig.open() as f:
        rows = list(csv.DictReader(f))
    if not rows:
        return 0.0, 0.0
    last = rows[-1]
    def num(k):
        try:
            return float(last.get(k, "") or 0.0)
        except ValueError:
            return 0.0
    feat = num("unique_features") / num("total_features") if num("total_features") else 0.0
    pairs = num("unique_pairs") / num("total_pairs") if num("total_pairs") else 0.0
    return feat, pairs


def load_opt_evenness(path: Path) -> Tuple[float, float]:
    stats = Path(path) / "mutator_optimization_stats.csv"
    if not stats.is_file():
        return 0.0, 0.0
    with stats.open() as f:
        reader = csv.DictReader(f)
        feats = [c for c in reader.fieldnames if c.startswith("OptEvent_")]
        totals: Dict[str, float] = {}
        for row in reader:
            for col in feats:
                try:
                    v = float(row.get(col, "0") or 0.0)
                except ValueError:
                    v = 0.0
                base = col[len("OptEvent_") :].rsplit("_", 1)[0]
                totals[base] = totals.get(base, 0.0) + v
        if not totals:
            return 0.0, 0.0
        h = entropy(totals)
        m = max(totals.values()) / sum(totals.values())
        return h, m


def plot_bar(xlabels: List[str], values: List[float], title: str, ylabel: str, out: Path, ylim: Tuple[float, float] | None = None):
    plt.figure(figsize=(8, 4))
    plt.bar(xlabels, values, color="#4C78A8")
    plt.title(title)
    plt.ylabel(ylabel)
    if ylim:
        plt.ylim(*ylim)
    plt.xticks(rotation=45, ha="right")
    plt.tight_layout()
    out.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(out, dpi=150)
    plt.close()
    print(f"[ok] wrote {out}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Coverage and evenness summary plots.")
    ap.add_argument("--root", required=True, help="Root with runs or signals/opt stats.")
    ap.add_argument("--output-prefix", default="summary", help="Output prefix for PNG files.")
    ap.add_argument("--per-scoring-evenness", action="store_true", help="Split evenness bars by scoring as well as corpus.")
    args = ap.parse_args()

    runs = parse_runs(Path(args.root).resolve())
    if not runs:
        raise SystemExit(f"No runs found under {args.root}")

    # Coverage by scoring
    cov = defaultdict(list)
    for r in runs:
        feat, pairs = load_final_signals(Path(r["path"]))
        cov[r["scoring"]].append((feat, pairs))
    scoring_labels = []
    feat_vals = []
    pair_vals = []
    for s in sorted(cov.keys()):
        feats = [v[0] for v in cov[s]]
        pairs = [v[1] for v in cov[s]]
        scoring_labels.append(s)
        feat_vals.append(np.mean(feats) if feats else 0.0)
        pair_vals.append(np.mean(pairs) if pairs else 0.0)
    plot_bar(scoring_labels, pair_vals, "Unique pairs / total (final) by scoring", "pairs ratio", Path(f"{args.output_prefix}_pairs_by_scoring.png"), ylim=(0,1))
    plot_bar(scoring_labels, feat_vals, "Unique features / total (final) by scoring", "features ratio", Path(f"{args.output_prefix}_features_by_scoring.png"), ylim=(0,1))

    # Evenness by corpus
    even = defaultdict(list)
    for r in runs:
        h, m = load_opt_evenness(Path(r["path"]))
        even[r["corpus"]].append((h, m))
    corpus_labels = []
    ent_vals = []
    max_vals = []
    for c in sorted(even.keys()):
        hs = [v[0] for v in even[c]]
        ms = [v[1] for v in even[c]]
        corpus_labels.append(c)
        ent_vals.append(np.mean(hs) if hs else 0.0)
        max_vals.append(np.mean(ms) if ms else 0.0)
    plot_bar(corpus_labels, ent_vals, "Opt-event evenness (entropy) by corpus", "normalized entropy", Path(f"{args.output_prefix}_entropy_by_corpus.png"), ylim=(0,1))
    plot_bar(corpus_labels, max_vals, "Opt-event max-share by corpus", "max share", Path(f"{args.output_prefix}_maxshare_by_corpus.png"), ylim=(0,1))

    if args.per_scoring_evenness:
        for s in sorted({r["scoring"] for r in runs}):
            labels, ents, maxs = [], [], []
            for c in sorted({r["corpus"] for r in runs}):
                hs = []
                ms = []
                for r in runs:
                    if r["scoring"] != s or r["corpus"] != c:
                        continue
                    h, m = load_opt_evenness(Path(r["path"]))
                    hs.append(h)
                    ms.append(m)
                labels.append(c)
                ents.append(np.mean(hs) if hs else 0.0)
                maxs.append(np.mean(ms) if ms else 0.0)
            plot_bar(labels, ents, f"Entropy by corpus (scoring={s})", "normalized entropy", Path(f"{args.output_prefix}_entropy_{s}.png"), ylim=(0,1))
            plot_bar(labels, maxs, f"Max-share by corpus (scoring={s})", "max share", Path(f"{args.output_prefix}_maxshare_{s}.png"), ylim=(0,1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
