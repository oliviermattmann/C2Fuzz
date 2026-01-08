package fuzzer.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;

/**
 * Writes periodic snapshots of the {@link GlobalStats} state to a CSV file so different
 * runtime configurations can be compared after a run.
 */
final class SignalRecorder {
    private static final Logger LOGGER = LoggingConfig.getLogger(SignalRecorder.class);

    private final Path logFile;
    private final Duration sampleInterval;
    private final AtomicBoolean headerWritten = new AtomicBoolean(false);
    private final Instant start = Instant.now();

    private volatile Instant nextSampleAt;

    SignalRecorder(Path logFile, Duration sampleInterval) {
        this.logFile = logFile;
        this.sampleInterval = (sampleInterval == null || sampleInterval.isNegative() || sampleInterval.isZero())
                ? Duration.ofMinutes(5)
                : sampleInterval;
        this.nextSampleAt = start.plus(this.sampleInterval);
    }

    void maybeRecord(GlobalStats stats) {
        Instant now = Instant.now();
        if (now.isBefore(nextSampleAt)) {
            return;
        }
        synchronized (this) {
            if (Instant.now().isBefore(nextSampleAt)) {
                return;
            }
            writeSnapshot(stats);
            nextSampleAt = Instant.now().plus(sampleInterval);
        }
    }

    /** Force a snapshot regardless of the interval (used at shutdown). */
    void recordNow(GlobalStats stats) {
        synchronized (this) {
            writeSnapshot(stats);
            nextSampleAt = Instant.now().plus(sampleInterval);
        }
    }

    /** Write a provided metrics snapshot (used to align with final metrics). */
    void recordSnapshot(GlobalStats stats, GlobalStats.FinalMetrics metrics) {
        synchronized (this) {
            writeSnapshot(stats, metrics);
            nextSampleAt = Instant.now().plus(sampleInterval);
        }
    }

    private void writeSnapshot(GlobalStats stats) {
        writeSnapshot(stats, stats.snapshotFinalMetrics());
    }

    private void writeSnapshot(GlobalStats stats, GlobalStats.FinalMetrics metrics) {
        Duration elapsed = Duration.between(start, Instant.now());

        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "Failed to create directories for signal log", ioe);
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            if (headerWritten.compareAndSet(false, true)) {
                writer.write("elapsed_seconds,total_dispatched,total_tests,scored_tests,failed_compilations,found_bugs,unique_features,total_features,unique_pairs,total_pairs,avg_score,max_score,int_timeouts,jit_timeouts,avg_runtime_weight,max_runtime_weight,min_runtime_weight,avg_int_runtime_ms,avg_jit_runtime_ms,avg_exec_runtime_ms,corpus_size,corpus_accepted,corpus_replaced,corpus_rejected,corpus_discarded\n");
            }

            String line = String.format(
                    "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%d,%d,%.6f,%.6f,%.6f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d%n",
                    elapsed.toSeconds(),
                    metrics.totalDispatched,
                    metrics.totalTests,
                    metrics.scoredTests,
                    metrics.failedCompilations,
                    metrics.foundBugs,
                    metrics.uniqueFeatures,
                    metrics.totalFeatures,
                    metrics.uniquePairs,
                    metrics.totalPairs,
                    metrics.avgScore,
                    metrics.maxScore,
                    stats.getIntTimeouts(),
                    stats.getJitTimeouts(),
                    stats.getAvgRuntimeWeight(),
                    stats.getMaxRuntimeWeight(),
                    stats.getMinRuntimeWeight(),
                    stats.getAvgIntExecTimeMillis(),
                    stats.getAvgJitExecTimeMillis(),
                    stats.getAvgExecTimeMillis(),
                    metrics.corpusSize,
                    metrics.corpusAccepted,
                    metrics.corpusReplaced,
                    metrics.corpusRejected,
                    metrics.corpusDiscarded);
            writer.write(line);
        } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "Failed to append signal snapshot", ioe);
        }
    }
}
