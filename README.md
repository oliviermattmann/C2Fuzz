# C2Fuzz

Greybox fuzzer for the HotSpot C2 JIT compiler, developed in the context of a Master's Thesis.

Supervisors:

- Theo Weidmann
- Manuel Hässig
- Cong Li

This codebase targets research runs, not yet production ready.

## Requirements

- Maven
- JDK 21+ for building C2Fuzz
- A **fastdebug** JDK build (used to execute generated tests)
- A **release** JDK build (used to run the persistent `javac_server`)

Instrumented JDK branch used in this project:
- https://github.com/oliviermattmann/jdk/tree/dev

## Build

```bash
mvn -DskipTests package
```

This creates a shaded runnable jar:
- `target/C2Fuzz-1.0-shaded.jar`

## Start the persistent javac server

The fuzzer offloads compilation to `javac_server`, so start it first.

```bash
export RELEASE_JDK=/abs/path/to/release/jdk

"$RELEASE_JDK/bin/javac" \
  -d javac_server/out \
  $(find javac_server/src/main/java -name '*.java')

"$RELEASE_JDK/bin/java" \
  -cp javac_server/out \
  com.example.javacserver.JavacServer \
  --bind 127.0.0.1 \
  --port 8090
```

Health check:

```bash
curl http://127.0.0.1:8090/health
```

Runtime host/port can be overridden via:
- `JAVAC_HOST`
- `JAVAC_PORT`

## Run the fuzzer

```bash
java -jar target/C2Fuzz-1.0-shaded.jar \
  --seeds /abs/path/to/seeds \
  --debug-jdk /abs/path/to/fastdebug/bin \
  --executors 4
```

Equivalent Maven entrypoint:

```bash
mvn exec:java -Dexec.args="--seeds /abs/path/to/seeds --debug-jdk /abs/path/to/fastdebug/bin"
```

## Runtime architecture (current)

- `SessionController`: session orchestration, worker lifecycle, shutdown, final report writing.
- `Executor`: compile + run testcases (`-Xint` and JIT), enqueue results.
- `Evaluator`: differential/assert checks, scoring, corpus decisions, scheduler feedback.
- `MutationWorker`: orchestration loop only (select parent, run batch, requeue/evict).
- `MutationInputSelector`: parent selection + execution queue capacity gating.
- `MutationAttemptEngine`: mutator choice, applicability checks, mutation attempt pipeline.
- `MutationOutputWriter`: Spoon sniper printing + testcase materialization.
- `GlobalStats` (+ split metric components): session/runtime/corpus/mutator metrics.

## Outputs

Per session outputs are written to:
- `logs/`
- `fuzz_sessions/`

Typical artifacts include metrics, signal traces, bug repro inputs, and queue snapshots.

## Command-line options

| Option | Description | Default / Notes |
|---|---|---|
| `--seeds <dir>` | Input seed directory. | **Required** |
| `--debug-jdk <bin-dir>` | Fastdebug JDK `bin/` directory used for executions. | **Required** |
| `--jdk <bin-dir>` | Alias for `--debug-jdk`. | Optional alias |
| `--mode <fuzz\|fuzz-asserts>` | Differential mode or assert-only mode. | `fuzz` |
| `--executors <n>` | Number of executor threads. | `4` |
| `--mutators <n>` | Number of mutator threads. | `1` |
| `--mutator-batch <n>` | Mutations attempted per selected parent. | `1` |
| `--mutator-timeout-ms <ms>` | Slow-mutation threshold (<=0 disables timeout). | `5000` |
| `--mutator-slow-limit <n>` | Slow-hit limit before parent eviction. | `5` |
| `--mutator-policy <UNIFORM\|BANDIT\|MOP>` | Mutator scheduling policy. | `UNIFORM` |
| `--corpus-policy <CHAMPION\|RANDOM>` | Corpus management policy. | `CHAMPION` |
| `--scoring <mode>` | Scoring mode (`PF_IDF`, `INTERACTION_DIVERSITY`, `INTERACTION_PAIR_WEIGHTED`, `UNIFORM`). | `PF_IDF` |
| `--rng <seed>` | Fixed RNG seed for reproducible runs. | Random seed per session if omitted |
| `--blacklist <file>` | Newline-delimited seed base names to skip. | Optional |
| `--signal-interval <sec>` | Signal CSV flush interval. | `300` |
| `--mutator-interval <sec>` | Mutator stats CSV interval (debug mode). | `300` |
| `--log-level <level>` | Java logging level (`INFO`, `FINE`, `WARNING`, etc.). | `INFO` |
| `--print-ast` | Print Spoon AST around successful mutation attempts. | Off |
| `--debug` | Enable debug-only recorders/output. | Off |

## Docker

Prerequisites:

- Build the custom JDK images on the host first. The Docker build expects these paths to exist:
  - `jdk/build/linux-x86_64-server-release/images/jdk`
  - `jdk/build/linux-x86_64-server-fastdebug/images/jdk`

  (the jdk directory should be in the root of this repository)
- Create host directories for mounted data:
  - `seeds/` (input corpus)
  - `logs/` and `fuzz_sessions/` (outputs)
- Put at least one valid seed corpus under `seeds/` (for example `seeds/runtime_compiler_filtered`).

Build:

```bash
docker build -t c2fuzz:slim .
```

Run (mount seeds and outputs):

```bash
docker run --rm -it \
  -u "$(id -u)":"$(id -g)" \
  -v "$(pwd)/seeds:/app/seeds" \
  -v "$(pwd)/logs:/app/logs" \
  -v "$(pwd)/fuzz_sessions:/app/fuzz_sessions" \
  c2fuzz:slim \
  --mode fuzz \
  --seeds /app/seeds/runtime_compiler_filtered \
  --blacklist /app/seeds/blacklist.txt
```

The container entrypoint starts `javac_server` automatically, then launches the fuzzer.

## Bugs found so far

14 reports in total (including duplicate reports).

- https://bugs.openjdk.org/browse/JDK-8370405
- https://bugs.openjdk.org/browse/JDK-8370416
- https://bugs.openjdk.org/browse/JDK-8370502
- https://bugs.openjdk.org/browse/JDK-8370948
- https://bugs.openjdk.org/browse/JDK-8373508 *(duplicate bug)*
- https://bugs.openjdk.org/browse/JDK-8373453
- https://bugs.openjdk.org/browse/JDK-8373525
- https://bugs.openjdk.org/browse/JDK-8373524
- https://bugs.openjdk.org/browse/JDK-8373569 *(duplicate bug)*
- https://bugs.openjdk.org/browse/JDK-8375618 (Found by JavaFuzzer first)
- https://bugs.openjdk.org/browse/JDK-8375645
- https://bugs.openjdk.org/browse/JDK-8376219
- https://bugs.openjdk.org/browse/JDK-8376587
- https://bugs.openjdk.org/browse/JDK-8377163
