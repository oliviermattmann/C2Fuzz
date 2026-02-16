package fuzzer.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import fuzzer.io.FileManager;
import fuzzer.logging.LoggingConfig;
import fuzzer.mutators.MutatorType;
import fuzzer.model.OptimizationVector;
import fuzzer.model.TestCase;
import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.runtime.monitoring.MutatorOptimizationRecorder;

final class SessionReportWriter {
    private static final Logger LOGGER = LoggingConfig.getLogger(SessionReportWriter.class);

    private final GlobalStats globalStats;
    private final FileManager fileManager;
    private final Instant sessionStart;
    private final MutatorOptimizationRecorder mutatorOptimizationRecorder;
    private final AtomicBoolean finalMetricsLogged = new AtomicBoolean(false);
    private final AtomicBoolean mutationQueueDumped = new AtomicBoolean(false);

    SessionReportWriter(GlobalStats globalStats,
                        FileManager fileManager,
                        Instant sessionStart,
                        MutatorOptimizationRecorder mutatorOptimizationRecorder) {
        this.globalStats = globalStats;
        this.fileManager = fileManager;
        this.sessionStart = sessionStart;
        this.mutatorOptimizationRecorder = mutatorOptimizationRecorder;
    }

    void writeFinalMetrics() {
        if (!finalMetricsLogged.compareAndSet(false, true)) {
            return;
        }
        long[] pairCountsSnapshot = globalStats.snapshotPairCounts();
        List<String> missingPairs = computeMissingPairs(pairCountsSnapshot);
        long totalPairsSnapshot = pairCountsSnapshot.length;
        long uniquePairsSnapshot = totalPairsSnapshot - missingPairs.size();
        GlobalStats.FinalMetrics baseMetrics = globalStats.snapshotFinalMetrics();
        GlobalStats.FinalMetrics metrics = new GlobalStats.FinalMetrics(
                baseMetrics.totalDispatched,
                baseMetrics.totalTests,
                baseMetrics.scoredTests,
                baseMetrics.failedCompilations,
                baseMetrics.foundBugs,
                baseMetrics.uniqueFeatures,
                baseMetrics.totalFeatures,
                uniquePairsSnapshot,
                totalPairsSnapshot,
                baseMetrics.avgScore,
                baseMetrics.maxScore,
                baseMetrics.corpusSize,
                baseMetrics.corpusAccepted,
                baseMetrics.corpusReplaced,
                baseMetrics.corpusRejected,
                baseMetrics.corpusDiscarded);
        Duration elapsed = Duration.between(sessionStart, Instant.now());
        String elapsedFormatted = formatElapsedDuration(elapsed);
        double elapsedSeconds = Math.max(0.0, elapsed.toMillis() / 1000.0);
        double featurePct = metrics.featureCoverageRatio() * 100.0;
        double pairPct = (totalPairsSnapshot == 0) ? 0.0 : (uniquePairsSnapshot * 100.0) / totalPairsSnapshot;
        long testsEvaluated = globalStats.getTotalTestsEvaluated();
        double dispatchThroughput = (elapsedSeconds > 0.0) ? metrics.totalDispatched / elapsedSeconds : 0.0;
        double evaluatedThroughput = (elapsedSeconds > 0.0) ? testsEvaluated / elapsedSeconds : 0.0;
        long intTimeouts = globalStats.getIntTimeouts();
        long jitTimeouts = globalStats.getJitTimeouts();
        double avgIntRuntimeMs = globalStats.getAvgIntExecTimeMillis();
        double avgJitRuntimeMs = globalStats.getAvgJitExecTimeMillis();
        double avgExecRuntimeMs = globalStats.getAvgExecTimeMillis();
        double avgCompilationMs = globalStats.getAvgCompilationTimeMillis();
        String summary = String.format(
                """
                Final run metrics:
                  tests dispatched: %,d
                  tests evaluated: %,d
                  scored tests: %,d
                  found bugs: %,d
                  failed compilations: %,d
                  interpreter timeouts: %,d
                  JIT timeouts: %,d
                  unique features seen: %d / %d (%.1f%%)
                  unique optimization pairs observed: %d / %d (%.1f%%)
                  average score: %.4f
                  maximum score: %.4f
                  average interpreter runtime: %.3f ms
                  average JIT runtime: %.3f ms
                  average exec runtime: %.3f ms
                  average compilation time: %.3f ms
                  throughput (dispatch/s): %.2f
                  throughput (evaluated/s): %.2f
                  total runtime: %s (%.3f s)
                """,
                metrics.totalDispatched,
                testsEvaluated,
                metrics.scoredTests,
                metrics.foundBugs,
                metrics.failedCompilations,
                intTimeouts,
                jitTimeouts,
                metrics.uniqueFeatures,
                metrics.totalFeatures,
                featurePct,
                uniquePairsSnapshot,
                totalPairsSnapshot,
                pairPct,
                metrics.avgScore,
                metrics.maxScore,
                avgIntRuntimeMs,
                avgJitRuntimeMs,
                avgExecRuntimeMs,
                avgCompilationMs,
                dispatchThroughput,
                evaluatedThroughput,
                elapsedFormatted,
                elapsedSeconds).stripTrailing();
        LOGGER.info(summary);
        if (mutatorOptimizationRecorder != null) {
            mutatorOptimizationRecorder.flush();
        }

        long championAccepted = globalStats.getChampionAccepted();
        long championReplaced = globalStats.getChampionReplaced();
        long championRejected = globalStats.getChampionRejected();
        long championDiscarded = globalStats.getChampionDiscarded();
        long championTotal = championAccepted + championReplaced + championRejected + championDiscarded;
        String championSummary = String.format(
                "Champion decisions: total %,d | accepted %,d | replaced %,d | rejected %,d | discarded %,d",
                championTotal,
                championAccepted,
                championReplaced,
                championRejected,
                championDiscarded);

        StringBuilder mutatorSummary = new StringBuilder("Mutators:\n");
        GlobalStats.MutatorStats[] mutatorStats = globalStats.snapshotMutatorStats();
        if (mutatorStats != null) {
            for (GlobalStats.MutatorStats ms : mutatorStats) {
                if (ms == null || ms.mutatorType == null || ms.mutatorType == MutatorType.SEED) {
                    continue;
                }
                long attempts = ms.mutationAttemptTotal();
                mutatorSummary.append(String.format(
                        "  %s: attempts %,d | success %,d | skip %,d | fail %,d | compFail %,d | bugs %,d%n",
                        ms.mutatorType.name(),
                        attempts,
                        ms.mutationSuccessCount,
                        ms.mutationSkipCount,
                        ms.mutationFailureCount,
                        ms.compileFailureCount,
                        ms.evaluationBugCount));
            }
        }

        Path sessionDir = (fileManager != null) ? fileManager.getSessionDirectoryPath() : null;
        if (sessionDir != null) {
            Path summaryFile = sessionDir.resolve("final_metrics.txt");
            String fileContent = summary + System.lineSeparator()
                    + championSummary + System.lineSeparator()
                    + mutatorSummary;
            try {
                Files.writeString(summaryFile, fileContent, StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                LOGGER.warning(String.format(
                        "Failed to write final metrics file %s: %s",
                        summaryFile,
                        ioe.getMessage()));
            }
            writeMissingPairs(sessionDir, missingPairs);
        } else {
            LOGGER.warning("Session directory unavailable; skipping final metrics file.");
        }
    }

    void dumpMutationQueueSnapshotCsv(BlockingQueue<TestCase> mutationQueue) {
        if (mutationQueue == null) {
            return;
        }
        if (!mutationQueueDumped.compareAndSet(false, true)) {
            return;
        }

        List<TestCase> snapshot = new ArrayList<>(mutationQueue);
        if (snapshot.isEmpty()) {
            LOGGER.info("Mutation queue empty; skipping CSV dump.");
            return;
        }

        snapshot.sort(Comparator.comparingDouble(TestCase::getScore).reversed());

        Path sessionDir = (fileManager != null) ? fileManager.getSessionDirectoryPath() : null;
        Path outputPath = (sessionDir != null)
                ? sessionDir.resolve("mutation_queue_snapshot.csv")
                : Path.of("mutation_queue_snapshot.csv");

        try (BufferedWriter writer = Files.newBufferedWriter(
                outputPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(String.join(",",
                    "rank",
                    "name",
                    "seedName",
                    "parentName",
                    "parentScore",
                    "score",
                    "priority",
                    "mutator",
                    "timesSelected",
                    "mutationDepth",
                    "mutationCount",
                    "activeChampion",
                    "interpreterRuntimeNanos",
                    "jitRuntimeNanos",
                    "hashedOptVector"));
            writer.write(System.lineSeparator());

            for (int i = 0; i < snapshot.size(); i++) {
                TestCase tc = snapshot.get(i);
                String hashedVector = "";
                int[] hashedOptVector = tc.getHashedOptVector();
                if (hashedOptVector != null) {
                    hashedVector = Arrays.toString(hashedOptVector);
                }
                String line = String.join(",",
                        csvValue(i + 1),
                        csvValue(tc.getName()),
                        csvValue(tc.getSeedName()),
                        csvValue(tc.getParentName()),
                        csvValue(tc.getParentScore()),
                        csvValue(tc.getScore()),
                        csvValue(tc.getPriority()),
                        csvValue(tc.getMutation() != null ? tc.getMutation().name() : ""),
                        csvValue(tc.getTimesSelected()),
                        csvValue(tc.getMutationDepth()),
                        csvValue(tc.getMutationCount()),
                        csvValue(tc.isActiveChampion()),
                        csvValue(tc.getInterpreterRuntimeNanos()),
                        csvValue(tc.getJitRuntimeNanos()),
                        csvValue(hashedVector));
                writer.write(line);
                writer.write(System.lineSeparator());
            }
            LOGGER.info(String.format(
                    "Dumped %d mutation-queue entries to %s",
                    snapshot.size(),
                    outputPath));
        } catch (IOException ioe) {
            LOGGER.warning(String.format(
                    "Failed to write mutation queue snapshot %s: %s",
                    outputPath,
                    ioe.getMessage()));
        }
    }

    private List<String> computeMissingPairs(long[] pairCountsSnapshot) {
        int features = globalStats.getFeatureSlots();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < features; i++) {
            for (int j = i + 1; j < features; j++) {
                int idx = globalStats.pairIdx(i, j);
                long count = (idx >= 0 && idx < pairCountsSnapshot.length) ? pairCountsSnapshot[idx] : 0L;
                if (count == 0L) {
                    String left = OptimizationVector.FeatureName(OptimizationVector.FeatureFromIndex(i));
                    String right = OptimizationVector.FeatureName(OptimizationVector.FeatureFromIndex(j));
                    missing.add(String.format("%02d,%02d\t%s | %s", i, j, left, right));
                }
            }
        }
        return missing;
    }

    private void writeMissingPairs(Path sessionDir, List<String> missingPairs) {
        Path target = sessionDir.resolve("missing_pairs.txt");
        try {
            if (missingPairs.isEmpty()) {
                Files.writeString(target, "All pairs observed.\n", StandardCharsets.UTF_8);
            } else {
                Files.write(target, missingPairs, StandardCharsets.UTF_8);
            }
        } catch (IOException ioe) {
            LOGGER.warning(String.format("Failed to write missing pairs file %s: %s", target, ioe.getMessage()));
        }
    }

    private static String formatElapsedDuration(Duration elapsed) {
        if (elapsed == null) {
            return "0:00:00";
        }
        long totalSeconds = Math.max(0L, elapsed.getSeconds());
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d:%02d", hours, minutes, seconds);
    }

    private static String csvValue(Object value) {
        if (value == null) {
            return "";
        }
        String str = String.valueOf(value);
        boolean needsQuotes = str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r");
        if (needsQuotes) {
            str = "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}
