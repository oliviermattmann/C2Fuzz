package fuzzer.runtime.monitoring;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.OptimizationVector;
import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;

/**
 * Tracks how each mutator influences the optimization vector and periodically
 * emits CSV snapshots so their behavior can be compared after a run.
 */
public final class MutatorOptimizationRecorder {
    private static final Logger LOGGER = LoggingConfig.getLogger(MutatorOptimizationRecorder.class);
    private static final int NUM_FEATURES = OptimizationVector.Features.values().length;
    private static final int[] ZERO_COUNTS = new int[NUM_FEATURES];

    private final Path logFile;
    private final Duration sampleInterval;
    private final GlobalStats globalStats;
    private final Instant start = Instant.now();
    private final AtomicBoolean headerWritten = new AtomicBoolean(false);
    private final AtomicLong totalRecorded = new AtomicLong();
    private volatile Instant nextDump;
    private final Map<MutatorType, FeatureDelta> deltaByMutator = new EnumMap<>(MutatorType.class);

    public MutatorOptimizationRecorder(Path logFile, Duration sampleInterval, GlobalStats globalStats) {
        this.logFile = logFile;
        this.sampleInterval = (sampleInterval == null || sampleInterval.isNegative() || sampleInterval.isZero())
                ? Duration.ofMinutes(5)
                : sampleInterval;
        this.globalStats = globalStats;
        this.nextDump = Instant.now().plus(this.sampleInterval);
        for (MutatorType type : MutatorType.values()) {
            deltaByMutator.put(type, new FeatureDelta(NUM_FEATURES));
        }
    }

    public void record(TestCase testCase) {
        if (testCase == null) {
            return;
        }
        MutatorType mutator = testCase.getMutation();
        if (mutator == null || mutator == MutatorType.SEED) {
            return;
        }

        OptimizationVectors childVectors = testCase.getOptVectors();
        if (childVectors == null) {
            return;
        }
        OptimizationVector childMerged = childVectors.mergedCounts();
        if (childMerged == null) {
            return;
        }
        OptimizationVectors parentVectors = testCase.getParentOptVectors();
        OptimizationVector parentMerged = (parentVectors != null) ? parentVectors.mergedCounts() : null;

        int[] childCounts = childMerged.counts;
        int[] parentCounts = (parentMerged != null) ? parentMerged.counts : ZERO_COUNTS;

        FeatureDelta delta = deltaByMutator.computeIfAbsent(mutator, t -> new FeatureDelta(NUM_FEATURES));
        delta.record(childCounts, parentCounts);

        long total = totalRecorded.incrementAndGet();
        maybeDump(total, Instant.now());
    }

    public void flush() {
        long total = totalRecorded.get();
        synchronized (this) {
            writeSnapshot(total);
        }
    }

    private void maybeDump(long total, Instant now) {
        if (now.isBefore(nextDump)) {
            return;
        }
        synchronized (this) {
            if (Instant.now().isBefore(nextDump)) {
                return;
            }
            writeSnapshot(total);
            nextDump = Instant.now().plus(sampleInterval);
        }
    }

    private void writeSnapshot(long total) {
        if (logFile == null) {
            return;
        }
        Path parentDir = logFile.getParent();
        if (parentDir != null) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Failed to create directories for mutator stats log", ioe);
                return;
            }
        }

        Map<MutatorType, GlobalStats.MutatorStats> statsByMutator = new EnumMap<>(MutatorType.class);
        if (globalStats != null) {
            GlobalStats.MutatorStats[] snapshot = globalStats.snapshotMutatorStats();
            if (snapshot != null) {
                for (GlobalStats.MutatorStats stats : snapshot) {
                    if (stats != null && stats.mutatorType != null) {
                        statsByMutator.put(stats.mutatorType, stats);
                    }
                }
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            if (headerWritten.compareAndSet(false, true)) {
                writer.write(buildHeader());
            }
            Duration elapsed = Duration.between(start, Instant.now());
            for (MutatorType type : MutatorType.values()) {
                if (type == null || type == MutatorType.SEED) {
                    continue;
                }
                FeatureDelta delta = deltaByMutator.computeIfAbsent(type, t -> new FeatureDelta(NUM_FEATURES));
                GlobalStats.MutatorStats stats = statsByMutator.get(type);
                boolean hasOptimizationSamples = delta != null && delta.samples > 0L;
                boolean hasStatusCounts = stats != null && (
                        stats.compileFailureCount > 0L
                                || stats.mutationSkipCount > 0L
                                || stats.mutationFailureCount > 0L
                                || stats.timeoutCount > 0L
                                || stats.evaluationFailureCount > 0L
                                || stats.evaluationTimeoutCount > 0L);
                if (!hasOptimizationSamples && !hasStatusCounts) {
                    continue;
                }
                writer.write(formatLine(elapsed, total, type, delta, stats));
            }
        } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "Failed to append mutator optimization stats", ioe);
        }
    }

    private static String buildHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("elapsed_seconds,total_records,mutator,total_samples,mutation_success,mutation_skip,mutation_failure,compile_failures,exec_timeouts,evaluation_failures,evaluation_timeouts");
        for (OptimizationVector.Features feature : OptimizationVector.Features.values()) {
            sb.append(',').append(feature.name()).append("_inc");
            sb.append(',').append(feature.name()).append("_dec");
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String formatLine(Duration elapsed,
                                     long total,
                                     MutatorType type,
                                     FeatureDelta delta,
                                     GlobalStats.MutatorStats stats) {
        long samples = (delta != null) ? delta.samples : 0L;
        long mutationSuccess = (stats != null) ? stats.mutationSuccessCount : 0L;
        long mutationSkip = (stats != null) ? stats.mutationSkipCount : 0L;
        long mutationFailure = (stats != null) ? stats.mutationFailureCount : 0L;
        long compileFailures = (stats != null) ? stats.compileFailureCount : 0L;
        long execTimeouts = (stats != null) ? stats.timeoutCount : 0L;
        long evaluationFailures = (stats != null) ? stats.evaluationFailureCount : 0L;
        long evaluationTimeouts = (stats != null) ? stats.evaluationTimeoutCount : 0L;

        StringBuilder sb = new StringBuilder();
        sb.append(elapsed.toSeconds()).append(',')
          .append(total).append(',')
          .append(type.name()).append(',')
          .append(samples).append(',')
          .append(mutationSuccess).append(',')
          .append(mutationSkip).append(',')
          .append(mutationFailure).append(',')
          .append(compileFailures).append(',')
          .append(execTimeouts).append(',')
          .append(evaluationFailures).append(',')
          .append(evaluationTimeouts);
        for (int i = 0; i < delta.increases.length; i++) {
            sb.append(',').append(delta.increases[i]);
            sb.append(',').append(delta.decreases[i]);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static final class FeatureDelta {
        final long[] increases;
        final long[] decreases;
        long samples;

        FeatureDelta(int numFeatures) {
            this.increases = new long[numFeatures];
            this.decreases = new long[numFeatures];
        }

        void record(int[] childCounts, int[] parentCounts) {
            if (childCounts == null) {
                return;
            }
            int length = childCounts.length;
            for (int i = 0; i < length; i++) {
                int parentVal = (parentCounts != null && parentCounts.length > i) ? parentCounts[i] : 0;
                int diff = childCounts[i] - parentVal;
                if (diff > 0) {
                    increases[i] += diff;
                } else if (diff < 0) {
                    decreases[i] += -diff;
                }
            }
            samples++;
        }
    }
}
