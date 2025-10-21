## Java Compiler Server

Minimal HTTP service that reuses a long-lived JVM process to compile single-file Java programs via the standard compiler API. Designed to eliminate the overhead of spawning a fresh JVM for every compilation.

### Prerequisites

- A full JDK (not just a JRE). Run the server using your custom JVM build by pointing to its `java` launcher.
- JDK 11 or newer is recommended (the code sticks to standard library features).

### Build

Compile the sources once with your custom JDK:

```bash
export JAVA_HOME=/path/to/custom-jdk
$JAVA_HOME/bin/javac -d out $(find src/main/java -name '*.java')
```

The `out` directory will contain the compiled server classes.

### Run

Start the server with the same custom JVM so that the in-process compiler comes from that build:

```bash
$JAVA_HOME/bin/java -cp out com.example.javacserver.JavacServer --port 8090 --bind 127.0.0.1
```

Command-line options:

- `--port <port>`: TCP port (default `8090`)
- `--bind <address>`: Bind address (default `127.0.0.1`)
- `--threads <count>`: Worker threads for compilation (default = available CPU cores)
- `--help`: Print usage

### Endpoints

- `GET /health` → returns `ok` if the service is alive.
- `POST /compile` → triggers compilation of a single Java source file.

### CLI client

`examples/compile_client.py` is a small helper that sends a compile request.

```bash
python3 examples/compile_client.py /tmp/Foo.java \
  --server http://127.0.0.1:8090/compile \
  --option -Xlint --option -g
```

Useful flags:

- `--server` to target another host/port (default `http://127.0.0.1:8090/compile`).
- `--class-output` to override the destination for `.class` files.
- `--option` (repeat) for additional compiler switches.
- `--raw-response` to print the server's raw JSON instead of a pretty dump.

#### Request parameters

You can pass parameters either as query params or JSON body:

- `sourcePath` (required): absolute or relative path to the `.java` file.
- `classOutput` (optional): directory for generated `.class` files. Defaults to the source file's directory; missing directories are created.
- `options` (optional): additional compiler flags. Supply either as a JSON array (`["-Xlint","-g"]`) or a whitespace-separated string (`"-Xlint -g"`). You may repeat the query parameter to pass multiple options.

Examples:

```bash
# Query-string request
curl -X POST 'http://127.0.0.1:8090/compile?sourcePath=/tmp/Foo.java'

# JSON body request with extra options
curl -X POST 'http://127.0.0.1:8090/compile' \
     -H 'Content-Type: application/json' \
     -d '{"sourcePath":"/tmp/Foo.java","options":["-Xlint","-g"]}'
```

#### Response

Successful compilation (`HTTP 200`):

```json
{
  "status": "ok",
  "success": true,
  "diagnostics": [],
  "generated": ["Foo.class"],
  "timestamp": "2024-05-01T12:34:56.789Z"
}
```

Failed compilation (`HTTP 400`) includes diagnostics:

```json
{
  "status": "error",
  "success": false,
  "diagnostics": [
    {
      "kind": "ERROR",
      "message": "missing return statement",
      "line": 12,
      "column": 5,
      "code": "compiler.err.missing.ret.stmt",
      "source": "/tmp/Foo.java"
    }
  ],
  "generated": [],
  "timestamp": "2024-05-01T12:35:12.123Z"
}
```

`generated` reports `.class` files whose timestamp is at or after the compilation request. The files are placed in the same directory as the source by default.

### Integrating with a fuzzer

Your fuzzer can keep the JVM process alive by:

1. Launching the server once with your custom JVM build.
2. Sending `POST /compile` requests with the path to the temporary source produced for each fuzz case.
3. Reading the JSON response to determine whether the compilation succeeded and to look at diagnostics on failure.

This approach avoids starting a new JVM per compilation while still using your custom runtime.

### Notes

- If `ToolProvider.getSystemJavaCompiler()` returns `null`, the JVM is not a full JDK. Confirm the server is launched using `bin/java` from your custom build.
- The server assumes a single top-level file per request; multi-file compilation is intentionally out of scope to keep the API simple.
