package fuzzer.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Builds normalized bug signatures and deterministic bucket identifiers.
 */
public final class BugBucketizer {
    private static final Logger LOGGER = LoggingConfig.getLogger(BugBucketizer.class);

    private static final Pattern HS_ERR_PATH_PATTERN = Pattern.compile("(\\S*hs_err_pid\\d+\\.log)");
    private static final Pattern SIGNAL_LINE_PATTERN = Pattern.compile("^#\\s+(SIG\\w+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROBLEMATIC_FRAME_MARKER = Pattern.compile("^#\\s*Problematic frame:", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_PATTERN = Pattern.compile("0x[0-9a-fA-F]+");

    public BugSignature bucketize(TestCaseResult tcr, Path testCasePath, String reason) {
        if (tcr == null) {
            return new BugSignature("b_unknown", reason, "", "", "", List.of(), -1, -1, "", "", "", null, "");
        }
        ExecutionResult intResult = tcr.intExecutionResult();
        ExecutionResult jitResult = tcr.jitExecutionResult();

        String combinedOut = mergeOutputs(jitResult);
        String hsErrPath = findFirstMatch(HS_ERR_PATH_PATTERN, combinedOut);
        HsErrSnapshot snapshot = parseHsErr(hsErrPath);

        String signal = snapshot.signal;
        String problematicFrame = snapshot.problematicFrame;
        String compileTask = snapshot.compileTask;
        List<String> topFrames = snapshot.topFrames;

        int intExit = (intResult != null) ? intResult.exitCode() : -1;
        int jitExit = (jitResult != null) ? jitResult.exitCode() : -1;
        String sourceHash = computeSourceHash(testCasePath);

        String mutation = (tcr.testCase().getMutation() != null)
                ? tcr.testCase().getMutation().name()
                : "";
        String seed = tcr.testCase().getSeedName();

        String canonical = buildCanonical(reason, signal, problematicFrame, compileTask, topFrames, intExit, jitExit, sourceHash, mutation, seed);
        String bucketId = "b_" + shortHash(canonical);

        return new BugSignature(
                bucketId,
                reason,
                signal,
                problematicFrame,
                compileTask,
                topFrames,
                intExit,
                jitExit,
                sourceHash,
                mutation,
                seed,
                hsErrPath,
                canonical);
    }

    private String buildCanonical(
            String reason,
            String signal,
            String problematicFrame,
            String compileTask,
            List<String> topFrames,
            int intExit,
            int jitExit,
            String sourceHash,
            String mutation,
            String seed) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "reason", reason);
        appendLine(sb, "signal", signal);
        appendLine(sb, "problematic_frame", problematicFrame);
        appendLine(sb, "compile_task", compileTask);
        if (topFrames != null && !topFrames.isEmpty()) {
            appendLine(sb, "top_frames", String.join("|", topFrames));
        }
        appendLine(sb, "int_exit", Integer.toString(intExit));
        appendLine(sb, "jit_exit", Integer.toString(jitExit));
        appendLine(sb, "source_hash", sourceHash);
        appendLine(sb, "mutation", mutation);
        appendLine(sb, "seed", seed);
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String key, String value) {
        sb.append(key)
          .append('=')
          .append(value == null ? "" : value.trim())
          .append('\n');
    }

    private String mergeOutputs(ExecutionResult jitResult) {
        if (jitResult == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (jitResult.stdout() != null) {
            sb.append(jitResult.stdout());
            if (!jitResult.stdout().endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (jitResult.stderr() != null) {
            sb.append(jitResult.stderr());
        }
        return sb.toString();
    }

    private String findFirstMatch(Pattern pattern, String haystack) {
        if (haystack == null) {
            return null;
        }
        Matcher m = pattern.matcher(haystack);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private HsErrSnapshot parseHsErr(String hsErrPath) {
        if (hsErrPath == null || hsErrPath.isBlank()) {
            return HsErrSnapshot.empty();
        }
        Path path = Path.of(hsErrPath);
        if (!Files.exists(path)) {
            LOGGER.warning(String.format("hs_err file referenced but not found: %s", hsErrPath));
            return HsErrSnapshot.empty();
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            return parseHsErrLines(lines, hsErrPath);
        } catch (IOException e) {
            LOGGER.warning(String.format("Unable to read hs_err file %s: %s", hsErrPath, e.getMessage()));
            return HsErrSnapshot.empty();
        }
    }

    private HsErrSnapshot parseHsErrLines(List<String> lines, String hsErrPath) {
        String signal = "";
        String problematicFrame = "";
        String compileTask = "";
        List<String> topFrames = new ArrayList<>();
        boolean takeProblematicNext = false;
        boolean captureCompileTask = false;
        boolean inNativeFrames = false;

        for (String line : lines) {
            if (signal.isEmpty()) {
                Matcher sigMatcher = SIGNAL_LINE_PATTERN.matcher(line);
                if (sigMatcher.find()) {
                    signal = sigMatcher.group(1).trim();
                }
            }

            if (PROBLEMATIC_FRAME_MARKER.matcher(line).find()) {
                takeProblematicNext = true;
                continue;
            }
            if (takeProblematicNext) {
                String norm = normalizeFrame(line);
                if (!norm.isBlank()) {
                    problematicFrame = norm;
                }
                takeProblematicNext = false;
                continue;
            }

            if (line.startsWith("Current CompileTask:")) {
                captureCompileTask = true;
                continue;
            }
            if (captureCompileTask) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    compileTask = trimmed;
                    captureCompileTask = false;
                }
                continue;
            }

            if (line.startsWith("Native frames:")) {
                inNativeFrames = true;
                continue;
            }
            if (inNativeFrames && topFrames.size() < 5) {
                String norm = normalizeFrame(line);
                if (!norm.isBlank()) {
                    topFrames.add(norm);
                }
                continue;
            }
            if (inNativeFrames && topFrames.size() >= 5) {
                // we already have enough for the signature; stop scanning this section
                inNativeFrames = false;
            }
        }

        return new HsErrSnapshot(signal, problematicFrame, compileTask, topFrames, hsErrPath);
    }

    private String normalizeFrame(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String withoutHex = HEX_PATTERN.matcher(trimmed).replaceAll("0x");
        String collapsedSpaces = withoutHex.replaceAll("\\s+", " ").trim();
        return collapsedSpaces;
    }

    private String computeSourceHash(Path testCasePath) {
        if (testCasePath == null) {
            return "";
        }
        try {
            if (!Files.exists(testCasePath)) {
                return "";
            }
            String source = Files.readString(testCasePath, StandardCharsets.UTF_8);
            String normalized = source
                    .replaceAll("c2fuzz\\d+", "CLASS")
                    .replaceAll("\\s+", " ")
                    .trim();
            return shortHash(normalized);
        } catch (IOException e) {
            LOGGER.warning(String.format("Unable to read test case source for hashing (%s): %s", testCasePath, e.getMessage()));
            return "";
        }
    }

    private String shortHash(String payload) {
        if (payload == null) {
            return "000000000000";
        }
        byte[] digest = digestSha256(payload.getBytes(StandardCharsets.UTF_8));
        return toHex(digest).substring(0, 12);
    }

    private byte[] digestSha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private record HsErrSnapshot(
            String signal,
            String problematicFrame,
            String compileTask,
            List<String> topFrames,
            String path) {
        static HsErrSnapshot empty() {
            return new HsErrSnapshot("", "", "", Collections.emptyList(), null);
        }
    }
}
