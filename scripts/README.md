# Seed Pipeline (Minimal)

This folder now keeps only the active seed-generation pipeline:

1. `filter_runtime_compiler_seeds.py`  
   Extracts flat, package-free candidate `.java` seeds from JDK jtreg runtime/compiler tests.
2. `filter_c2_optimized_seeds.py`  
   Keeps only seeds that actually produce C2 optimization markers and copies accepted files.

## Full run

From repo root:

```bash
python3 scripts/filter_runtime_compiler_seeds.py \
  --jdk-tests jdk/test/hotspot/jtreg \
  --output seeds/runtime_compiler

python3 scripts/filter_c2_optimized_seeds.py \
  --seeds seeds/runtime_compiler \
  --java jdk/build/linux-x86_64-server-fastdebug/jdk/bin/java \
  --javac jdk/build/linux-x86_64-server-fastdebug/jdk/bin/javac \
  --output seeds/runtime_compiler_c2_names.txt \
  --accepted-dir seeds/runtime_compiler_c2 \
  --jobs 4
```

Outputs:
- `seeds/runtime_compiler`: flat candidate seeds
- `seeds/runtime_compiler_c2_names.txt`: accepted seed filenames
- `seeds/runtime_compiler_c2`: flat accepted seeds (package lines removed)

## Quick smoke test

```bash
python3 scripts/filter_c2_optimized_seeds.py \
  --seeds seeds/runtime_compiler \
  --java jdk/build/linux-x86_64-server-fastdebug/jdk/bin/java \
  --javac jdk/build/linux-x86_64-server-fastdebug/jdk/bin/javac \
  --output /tmp/c2_names_smoke.txt \
  --accepted-dir /tmp/c2_seeds_smoke \
  --jobs 1 --timeout 5 --compile-timeout 20 --skip-flag-check
```

## Runtime note

This pipeline can take a while.  
The first script scans many jtreg files, and the second script compiles/runs each candidate.  
On a full dataset, expect it to take from tens of minutes to multiple hours depending on machine and `--jobs`.
