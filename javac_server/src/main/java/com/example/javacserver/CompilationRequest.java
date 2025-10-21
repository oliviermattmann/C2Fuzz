package com.example.javacserver;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class CompilationRequest {
    private final Path sourcePath;
    private final Path outputDirectory;
    private final List<String> compilerOptions;

    private CompilationRequest(Path sourcePath, Path outputDirectory, List<String> compilerOptions) {
        this.sourcePath = sourcePath;
        this.outputDirectory = outputDirectory;
        this.compilerOptions = compilerOptions;
    }

    Path getSourcePath() {
        return sourcePath;
    }

    Path getOutputDirectory() {
        return outputDirectory;
    }

    List<String> getCompilerOptions() {
        return compilerOptions;
    }

    static CompilationRequest fromExchange(HttpExchange exchange) throws IOException {
        Map<String, List<String>> queryParams = HttpUtils.parseQueryParams(exchange);
        String contentType = HttpUtils.getContentType(exchange);

        Map<String, Object> bodyMap = new HashMap<>();
        String body = HttpUtils.readRequestBody(exchange).trim();
        if (!body.isEmpty()) {
            bodyMap.putAll(parseBody(contentType, body));
        }

        String sourcePathValue = firstNonBlank(
                asString(bodyMap.get("sourcePath")),
                asString(bodyMap.get("source_path")),
                HttpUtils.getSingleParam(queryParams, "sourcePath"),
                HttpUtils.getSingleParam(queryParams, "source_path")
        );

        if (sourcePathValue == null || sourcePathValue.isBlank()) {
            if (!body.isEmpty() && !looksStructured(contentType, body)) {
                sourcePathValue = body;
            } else {
                throw new IllegalArgumentException("Missing sourcePath. Provide it as JSON, form data, query parameter, or raw body.");
            }
        }

        Path sourcePath = Paths.get(sourcePathValue).toAbsolutePath().normalize();
        if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("sourcePath does not exist or is not a file: " + sourcePath);
        }

        Path outputDirectory = resolveOutputDirectory(sourcePath,
                firstNonBlank(
                        asString(bodyMap.get("classOutput")),
                        asString(bodyMap.get("outputDirectory")),
                        asString(bodyMap.get("class_output")),
                        HttpUtils.getSingleParam(queryParams, "classOutput"),
                        HttpUtils.getSingleParam(queryParams, "outputDirectory"),
                        HttpUtils.getSingleParam(queryParams, "class_output")
                ));

        List<String> compilerOptions = mergeOptions(queryParams, bodyMap);

        return new CompilationRequest(sourcePath, outputDirectory, compilerOptions);
    }

    private static Path resolveOutputDirectory(Path sourcePath, String requestedOutputDir) throws IOException {
        if (requestedOutputDir == null || requestedOutputDir.isBlank()) {
            Path parent = sourcePath.getParent();
            if (parent == null) {
                return Paths.get(".").toAbsolutePath().normalize();
            }
            return parent.toAbsolutePath().normalize();
        }
        Path outputDir = Paths.get(requestedOutputDir).toAbsolutePath().normalize();
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        if (!Files.isDirectory(outputDir)) {
            throw new IllegalArgumentException("classOutput must be a directory: " + outputDir);
        }
        return outputDir;
    }

    private static List<String> mergeOptions(Map<String, List<String>> queryParams, Map<String, Object> bodyMap) {
        List<String> options = new ArrayList<>();
        Object bodyOptions = firstPresent(
                bodyMap.get("options"),
                bodyMap.get("compilerOptions"),
                bodyMap.get("compiler_options"));
        if (bodyOptions instanceof List<?>) {
            for (Object value : (List<?>) bodyOptions) {
                if (value != null) {
                    options.add(value.toString());
                }
            }
        } else if (bodyOptions instanceof String) {
            Collections.addAll(options, splitOptions((String) bodyOptions));
        }

        List<String> queryOptions = firstNonNullList(
                queryParams.get("options"),
                queryParams.get("option"),
                queryParams.get("compilerOptions"));
        if (queryOptions != null) {
            for (String opt : queryOptions) {
                if (opt != null && !opt.isBlank()) {
                    Collections.addAll(options, splitOptions(opt));
                }
            }
        }

        return options;
    }

    private static String[] splitOptions(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("\\s+");
    }

    private static Map<String, Object> parseBody(String contentType, String body) {
        if (contentType.isEmpty()) {
            if (looksJson(body)) {
                return SimpleJson.parseObject(body);
            }
            if (body.contains("=") && body.contains("&")) {
                return toSingleValueMap(HttpUtils.parseQuery(body));
            }
            return Collections.emptyMap();
        }
        switch (contentType) {
            case "application/json":
                return SimpleJson.parseObject(body);
            case "application/x-www-form-urlencoded":
                return toSingleValueMap(HttpUtils.parseQuery(body));
            case "text/plain":
                return Collections.emptyMap();
            default:
                return Collections.emptyMap();
        }
    }

    private static boolean looksStructured(String contentType, String body) {
        if (!contentType.isEmpty()) {
            return true;
        }
        return looksJson(body) || (body.contains("=") && body.contains("&"));
    }

    private static boolean looksJson(String body) {
        String trimmed = body.trim();
        return !trimmed.isEmpty() && trimmed.charAt(0) == '{';
    }

    private static Map<String, Object> toSingleValueMap(Map<String, List<String>> params) {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (values.size() == 1) {
                map.put(entry.getKey(), values.get(0));
            } else {
                map.put(entry.getKey(), new ArrayList<>(values));
            }
        }
        return map;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private static Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> List<T> firstNonNullList(List<T>... lists) {
        if (lists == null) {
            return null;
        }
        for (List<T> list : lists) {
            if (list != null && !list.isEmpty()) {
                return list;
            }
        }
        return null;
    }
}
