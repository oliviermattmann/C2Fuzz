# Notice
Many of the scripts here are out of date...

## run_scoring_experiments.py

`run_scoring_experiments.py` sweeps all scoring modes (parsed from
`src/main/java/fuzzer/runtime/ScoringMode.java`) for each requested mutator
policy (`UNIFORM` and `BANDIT` by default). Every run is allowed to execute for
the configured duration before the script interrupts the fuzzer, after which
the generated session directory (plus logs and any archived best-cases) is
relocated into a dedicated results folder.

Basic usage:

```bash
python3 scripts/run_scoring_experiments.py \
  --seeds seeds/test \
  --debug-jdk /path/to/jdk/bin \
  --duration-minutes 15 \
  --fuzzer-args --executors 8
```

Key options:

- `--output-dir` chooses where the renamed session folders are stored
  (defaults to `fuzz_sessions/scoring_sweep_<timestamp>`).
- `--modes` / `--policies` restrict the sweep to a subset (e.g.
  `--modes PF_IDF INTERACTION_DIVERSITY`).
- Additional CLI flags for the fuzzer can be appended after `--fuzzer-args`.
