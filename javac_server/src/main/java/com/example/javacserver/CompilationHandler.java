package com.example.javacserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class CompilationHandler implements HttpHandler {
    private final JavaCompiler compiler;

    CompilationHandler(JavaCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendPlainText(exchange, 405, "POST required");
            return;
        }

        try {
            CompilationRequest request = CompilationRequest.fromExchange(exchange);
            CompilationResponse response = compile(request);
            int status = response.success ? 200 : 400;
            HttpUtils.sendJson(exchange, status, response.toMap());
        } catch (IllegalArgumentException ex) {
            HttpUtils.sendJson(exchange, 400, HttpUtils.buildErrorPayload(ex.getMessage()));
        } catch (Exception ex) {
            Map<String, Object> payload = HttpUtils.buildErrorPayload("Unexpected server error: " + ex.getMessage());
            payload.put("exception", ex.getClass().getName());
            HttpUtils.sendJson(exchange, 500, payload);
        }
    }

    private CompilationResponse compile(CompilationRequest request) throws IOException {
        Instant start = Instant.now();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(request.getOutputDirectory().toFile()));

            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(
                    List.of(request.getSourcePath()));

            List<String> options = new ArrayList<>();
            AtomicBoolean hasDestinationOption = new AtomicBoolean(false);
            for (String option : request.getCompilerOptions()) {
                options.add(option);
                if ("-d".equals(option)) {
                    hasDestinationOption.set(true);
                }
            }
            if (!hasDestinationOption.get()) {
                options.add("-d");
                options.add(request.getOutputDirectory().toString());
            }

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits);

            boolean success = Boolean.TRUE.equals(task.call());

            List<Map<String, Object>> diagnosticsPayload = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                diagnosticsPayload.add(toDiagnosticPayload(diagnostic));
            }

            List<String> generated = success
                    ? collectGeneratedClassFiles(request.getOutputDirectory(), start)
                    : List.of();

            return new CompilationResponse(success, diagnosticsPayload, generated, start);
        }
    }

    private List<String> collectGeneratedClassFiles(Path outputDir, Instant start) throws IOException {
        List<String> generated = new ArrayList<>();
        if (!Files.isDirectory(outputDir)) {
            return generated;
        }
        try (var stream = Files.walk(outputDir)) {
            stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                    .filter(path -> {
                        try {
                            return !Files.getLastModifiedTime(path).toInstant().isBefore(start);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(path -> generated.add(outputDir.relativize(path).toString()));
        }
        return generated;
    }

    private Map<String, Object> toDiagnosticPayload(Diagnostic<? extends JavaFileObject> diagnostic) {
        Map<String, Object> map = new HashMap<>();
        map.put("kind", diagnostic.getKind().name());
        map.put("message", diagnostic.getMessage(null));
        map.put("line", diagnostic.getLineNumber());
        map.put("column", diagnostic.getColumnNumber());
        map.put("code", diagnostic.getCode());
        JavaFileObject source = diagnostic.getSource();
        if (source != null) {
            map.put("source", source.toUri().getPath());
        }
        return map;
    }

    private static final class CompilationResponse {
        private final boolean success;
        private final List<Map<String, Object>> diagnostics;
        private final List<String> generatedFiles;
        private final Instant startedAt;

        private CompilationResponse(boolean success,
                                    List<Map<String, Object>> diagnostics,
                                    List<String> generatedFiles,
                                    Instant startedAt) {
            this.success = success;
            this.diagnostics = diagnostics;
            this.generatedFiles = generatedFiles;
            this.startedAt = startedAt;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("status", success ? "ok" : "error");
            map.put("success", success);
            map.put("diagnostics", diagnostics);
            map.put("generated", generatedFiles);
            map.put("timestamp", startedAt.toString());
            return map;
        }
    }
}
