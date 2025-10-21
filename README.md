# hotspot-jit-fuzzer
Fuzzer for the Hotspot JIT Compiler developed as Master Thesis Project

## Building the standalone jar

```bash
mvn -DskipTests package
```

The build emits a dependency-included jar at `target/spoon-demo-1.0-SNAPSHOT-shaded.jar`. Copy this jar, the `scripts` directory, and any required seed inputs to your execution environment.

## Running on a shared cluster

Use `scripts/run_fuzzer_cluster.sh` to constrain the fuzzer to specific CPU cores and supply the JDK locations needed for interpreter and JIT runs.

```bash
scripts/run_fuzzer_cluster.sh \
  --seeds /path/to/seeds \
  --jdk /cluster/jdks/jdk-build/bin \
  --executors 4 \
  --cores 0-3 \
  -- --rng 12345
```

Key points:
- `--jdk` applies the same path to both debug and release builds; override with `--debug-jdk`/`--release-jdk` if you have separate binaries.
- Set `CORES`, `EXECUTORS`, `JAR_PATH`, or `JAVA_BIN` environment variables to change defaults without editing the script.
- Additional arguments after `--` are forwarded directly to the fuzzer.
