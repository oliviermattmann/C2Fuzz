# Spoon Playground

Standalone CLI to load Java testcases with Spoon and print their AST. Use it to quickly check how Spoon models new Java features without touching the main fuzzer.

## Build

```bash
cd spoon_playground
mvn -q -DskipTests package
```

The shaded binary lands in `target/spoon-playground-1.0-SNAPSHOT-shaded.jar`.

## Run

```bash
java -jar target/spoon-playground-1.0-SNAPSHOT-shaded.jar \
  --input /path/to/testcases \
  [--compliance 21] [--preview] [--with-classpath] [--strict]
```

Options:
- `--input <dir>`: required root containing `.java` testcases (walks recursively).
- `--compliance <level>`: Java version passed to Spoon (default `21`).
- `--preview`: enable preview features in Spoon’s environment.
- `--with-classpath`: disable Spoon’s `--noclasspath` mode if the sources need resolution.
- `--strict`: fail on syntax errors (default is to ignore and move on).
- `--verbose`: print stack traces on failures.

The tool prints a file header and then the indented AST using the bundled `AstTreePrinter`.
