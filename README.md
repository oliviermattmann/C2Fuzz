# C2Fuzz
A whitebox fuzzer for the Hotspot C2 JIT Compiler developed as part of my Master's Thesis

Keep in mind that this fuzzer is still work in progress and is subject to many changes. It has many quirks and is far from perfect. As such it runs and occasionally finds bugs.

## Prerequesites
1) Maven
2) a JVM build (release and fastdebug) of the instrumented JVM code. You can clone the repo here: [https://github.com/oliviermattmann/jdk/tree/dev](https://github.com/oliviermattmann/jdk/tree/dev)

## Build

You need jdk version >= 21.

```bash
mvn -DskipTests package
```

The build emits a dependency-included jar at `target/C2Fuzz-1.0-shaded.jar`.

## Start the persistent javac server

Compilation is offloaded to `javac_server`, which must be running *before* the fuzzer starts. Launch it with a **release** JDK (ideally the same version as your debug build) so `ToolProvider.getSystemJavaCompiler()` is available.

1. export a temporary ENV variable for the session or use absolute paths of the release build
   ```bash
   export RELEASE_JDK=/abs/path/to/release/jdk
   ```


2. Compile the server classes once:

   ```bash
   "$RELEASE_JDK/bin/javac" \
     -d javac_server/out \
     $(find javac_server/src/main/java -name '*.java')
   ```

3. Start the HTTP service (defaults: `127.0.0.1:8090`):

   ```bash
   "$RELEASE_JDK/bin/java" \
     -cp javac_server/out \
     com.example.javacserver.JavacServer \
     --bind 127.0.0.1 \
     --port 8090
   ```

   Leave this process running. The fuzzer talks to `http://127.0.0.1:8090/compile`. Use `JAVAC_HOST` / `JAVAC_PORT` environment variables if you bind it elsewhere and want the runtime to follow suit. A quick health check:

   ```bash
   curl http://127.0.0.1:8090/health
   ```

## Run the fuzzer locally

With the server running, launch the fuzzer with your **fastdebug** JDK (that is the VM we execute under `-Xint` and C2):

```bash
java -jar target/C2Fuzz-1.0-shaded.jar \
  --seeds /abs/path/to/seeds \
  --debug-jdk /abs/path/to/fastdebug/bin \
  --executors 4
```
I like to use the these seeds (fast and diverse): 'seeds/selfcontained_jtreg_compiler_positive_pf_idf'

It is possible to use just one seed to start, that way you can look for edge cases in the seed test case. For some seeds this does not work well with the current default scoring (PF-IDF). Workaround is to use one of the other scoring methods available.

If you prefer a shorter flag, `--jdk` is an alias for `--debug-jdk`. When `--rng` is omitted the fuzzer picks a fresh random seed per session.

Environment fallbacks:
- `C2FUZZ_DEBUG_JDK` fills in a missing `--debug-jdk`/`--jdk` value.
- `C2FUZZ_SCORING` (or JVM property `c2fuzz.scoring`) chooses the scoring mode when `--scoring` is not provided.
- `C2FUZZ_MUTATOR_POLICY` (or JVM property `c2fuzz.mutatorPolicy`) selects the mutator scheduler if `--mutator-policy` is absent.
- `C2FUZZ_LOG_LEVEL` (or JVM property `c2fuzz.logLevel`) selects the log verbosity when `--log-level` is absent.
- `JAVAC_HOST` / `JAVAC_PORT` tell the runtime where to reach the compiler service (defaults `127.0.0.1:8090`).

Logs and session artefacts are written under `logs/` and `fuzz_sessions/` using the timestamp printed at startup.

## Command-Line Options

| Option | Description | Default / Notes |
| ------ | ----------- | --------------- |
| `--seeds <dir>` | Directory containing initial Java seeds. | **Required**. |
| `--seedpool <dir>` | Persistent corpus location for interesting seeds. | Disabled unless provided. |
| `--jdk <bin-dir>` | Alias for `--debug-jdk`. | Same as `--debug-jdk`. |
| `--debug-jdk <bin-dir>` | Path to fastdebug JDK `bin/` used to run interpreter and JIT executions. | Defaults to `C2FUZZ_DEBUG_JDK`, then project-local build. |
| `--executors <n>` | Number of parallel executor threads. | `4`. Must be positive. |
| `--rng <seed>` | Fix the RNG seed for reproducibility. | Random per launch if omitted or invalid. |
| `--scoring <mode>` | Select scoring heuristic (`PF_IDF`, `ABSOLUTE_COUNT`, `PAIR_COVERAGE`, `INTERACTION_DIVERSITY`, `NOVEL_FEATURE_BONUS`). | `PF_IDF`. |
| `--mode <kind>` | Choose runtime mode: `fuzz`, `fuzz-asserts`, or `test-mutator`. | `fuzz`. |
| `--mutator-policy <policy>` | Scheduling policy for picking mutators (`UNIFORM`, `BANDIT`). | `UNIFORM`. |
| `--log-level <level>` | Java util logging level (`INFO`, `FINE`, `WARN`, etc.). | `INFO`. |
| `--print-ast` | Dump Spoon AST of transformed seeds for debugging. | Off by default. |
| `--mutator <MutatorType>` | Enter targeted mutator test mode for the named enum constant. | Forces `test-mutator` mode. |
| `--test-mutator-seeds <n>` | Number of seeds sampled when in test-mutator mode. | `5`. |
| `--test-mutator-iterations <n>` | Mutations per sampled seed in test-mutator mode. | `3`. |



## Bugs found so far

- https://bugs.openjdk.org/browse/JDK-8370948
- https://bugs.openjdk.org/browse/JDK-8370405
- https://bugs.openjdk.org/browse/JDK-8370502
- https://bugs.openjdk.org/browse/JDK-8370416
