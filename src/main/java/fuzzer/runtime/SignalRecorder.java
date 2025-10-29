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
    private final long samplePeriod;
    private final AtomicBoolean headerWritten = new AtomicBoolean(false);
    private final Instant start = Instant.now();

    private volatile long nextSampleAt;

    SignalRecorder(Path logFile, long samplePeriod) {
        this.logFile = logFile;
        this.samplePeriod = Math.max(1L, samplePeriod);
        this.nextSampleAt = samplePeriod;
    }

    void maybeRecord(GlobalStats stats) {
        long totalTests = stats.getTotalTestsExecuted();
        if (totalTests < nextSampleAt) {
            return;
        }
        synchronized (this) {
            if (totalTests < nextSampleAt) {
                return;
            }
            writeSnapshot(stats);
            long multiple = Math.max(1L, totalTests / samplePeriod);
            nextSampleAt = (multiple + 1L) * samplePeriod;
        }
    }

    private void writeSnapshot(GlobalStats stats) {
        GlobalStats.FinalMetrics metrics = stats.snapshotFinalMetrics();
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
                writer.write("elapsed_seconds,total_tests,scored_tests,failed_compilations,found_bugs,unique_features,total_features,unique_pairs,total_pairs,avg_score,max_score,int_timeouts,jit_timeouts,avg_runtime_weight,max_runtime_weight,min_runtime_weight\n");
            }

            String line = String.format(
                    "%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%d,%d,%.6f,%.6f,%.6f%n",
                    elapsed.toSeconds(),
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
                    stats.getMinRuntimeWeight());
            writer.write(line);
        } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "Failed to append signal snapshot", ioe);
        }
    }
}
