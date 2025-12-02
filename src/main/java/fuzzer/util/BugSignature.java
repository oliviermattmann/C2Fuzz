package fuzzer.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable representation of a normalized bug signature.
 */
public final class BugSignature {
    private final String bucketId;
    private final String reason;
    private final String signal;
    private final String problematicFrame;
    private final String compileTask;
    private final List<String> topFrames;
    private final int interpreterExitCode;
    private final int jitExitCode;
    private final String sourceHash;
    private final String mutation;
    private final String seed;
    private final String hsErrPath;
    private final String canonical;

    public BugSignature(
            String bucketId,
            String reason,
            String signal,
            String problematicFrame,
            String compileTask,
            List<String> topFrames,
            int interpreterExitCode,
            int jitExitCode,
            String sourceHash,
            String mutation,
            String seed,
            String hsErrPath,
            String canonical) {
        this.bucketId = Objects.requireNonNullElse(bucketId, "");
        this.reason = Objects.requireNonNullElse(reason, "");
        this.signal = Objects.requireNonNullElse(signal, "");
        this.problematicFrame = Objects.requireNonNullElse(problematicFrame, "");
        this.compileTask = Objects.requireNonNullElse(compileTask, "");
        this.topFrames = (topFrames == null) ? List.of() : List.copyOf(topFrames);
        this.interpreterExitCode = interpreterExitCode;
        this.jitExitCode = jitExitCode;
        this.sourceHash = Objects.requireNonNullElse(sourceHash, "");
        this.mutation = Objects.requireNonNullElse(mutation, "");
        this.seed = Objects.requireNonNullElse(seed, "");
        this.hsErrPath = hsErrPath;
        this.canonical = Objects.requireNonNullElse(canonical, "");
    }

    public String bucketId() {
        return bucketId;
    }

    public String reason() {
        return reason;
    }

    public String signal() {
        return signal;
    }

    public String problematicFrame() {
        return problematicFrame;
    }

    public String compileTask() {
        return compileTask;
    }

    public List<String> topFrames() {
        return Collections.unmodifiableList(topFrames);
    }

    public int interpreterExitCode() {
        return interpreterExitCode;
    }

    public int jitExitCode() {
        return jitExitCode;
    }

    public String sourceHash() {
        return sourceHash;
    }

    public String mutation() {
        return mutation;
    }

    public String seed() {
        return seed;
    }

    public String hsErrPath() {
        return hsErrPath;
    }

    public String canonical() {
        return canonical;
    }

    public List<String> toSummaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("bucket_id: " + bucketId);
        lines.add("reason: " + safe(reason));
        lines.add("signal: " + safe(signal));
        lines.add("problematic_frame: " + safe(problematicFrame));
        lines.add("compile_task: " + safe(compileTask));
        if (!topFrames.isEmpty()) {
            lines.add("top_frames: " + String.join(" | ", topFrames));
        }
        lines.add("interpreter_exit: " + interpreterExitCode);
        lines.add("jit_exit: " + jitExitCode);
        lines.add("source_hash: " + safe(sourceHash));
        lines.add("mutation: " + safe(mutation));
        lines.add("seed: " + safe(seed));
        if (hsErrPath != null && !hsErrPath.isBlank()) {
            lines.add("hs_err: " + hsErrPath);
        }
        return lines;
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? "n/a" : value;
    }
}
