package fuzzer.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import fuzzer.mutators.MutatorType;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;

final class ConsoleDashboard {
    private final GlobalStats gs;
    private boolean firstDraw = true;
    private int lastLines = 0;
    private final Instant start = Instant.now();

    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCaseResult> evaluationQueue;
    private final BlockingQueue<TestCase> executionQueue;
    private final Runnable onShutdown;
    private boolean shutdownInvoked = false;

    ConsoleDashboard(GlobalStats gs,
                     BlockingQueue<TestCase> mutationQueue,
                     BlockingQueue<TestCaseResult> evaluationQueue,
                     BlockingQueue<TestCase> executionQueue,
                     Runnable onShutdown) {
        this.gs = gs;
        this.mutationQueue = mutationQueue;
        this.evaluationQueue = evaluationQueue;
        this.executionQueue = executionQueue;
        this.onShutdown = onShutdown;
    }

    void run(Duration interval) {
        boolean fancy = System.console() != null && supportsAnsi();
        if (fancy) hideCursor();
        try {
            while (true) {
                List<String> lines = renderLines();
                if (fancy) redrawInPlace(lines);
                else lines.forEach(System.out::println);
                Thread.sleep(interval.toMillis());
                if (!fancy) System.out.println();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            invokeShutdownOnce();
        } finally {
            invokeShutdownOnce();
            if (fancy) {
                restoreCursor();
                System.out.print("\u001B[" + Math.max(0, lastLines - 1) + "E\r");
            }
            System.out.flush();
        }
    }

    private void invokeShutdownOnce() {
        if (!shutdownInvoked) {
            shutdownInvoked = true;
            if (onShutdown != null) {
                onShutdown.run();
            }
        }
    }

    private List<String> renderLines() {
        List<String> out = new ArrayList<>();
        long dispatched = gs.getTotalTestsDispatched();
        long evaluated = gs.getTotalTestsEvaluated();
        long executed = gs.getTotalTestsExecuted();
        long failedComps = gs.getFailedCompilations();

        double minutes = Math.max(1.0, Duration.between(start, Instant.now()).toMinutes());
        double avgThroughput = evaluated / minutes;

        long foundBugs = gs.getFoundBugs();
        long uniqueBugs = gs.getUniqueBugBuckets();
        int mutQueueSize = mutationQueue.size();
        int execQueueSize = executionQueue.size();
        int evalQueueSize = evaluationQueue.size();
        double avgIntExecMillis = gs.getAvgIntExecTimeMillis();
        double avgJitExecMillis = gs.getAvgJitExecTimeMillis();
        double avgCombinedExecMillis = gs.getAvgExecTimeMillis();
        double avgCompilationMillis = gs.getAvgCompilationTimeMillis();
        double avgRuntimeWeight = gs.getAvgRuntimeWeight();
        double maxRuntimeWeight = gs.getMaxRuntimeWeight();
        double minRuntimeWeight = gs.getMinRuntimeWeight();
        GlobalStats.MutatorStats[] mutatorStats = gs.snapshotMutatorStats();

        Map<String, Long> freq = new java.util.HashMap<>();
        gs.getOpFreqMap().forEach((k, v) -> freq.put(k, v.sum()));

        List<Map.Entry<String, Long>> top = new ArrayList<>(freq.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        Duration up = Duration.between(start, Instant.now());

        out.add(String.format("FUZZER DASHBOARD  |  up %s", human(up)));
        out.add("────────────────────────────────────────────────────────────");
        out.add(String.format(
                "Tests disp %,d | eval %,d | exec %,d | bugs %,d (unique %,d) | failed comp %,d",
                dispatched, evaluated, executed, foundBugs, uniqueBugs, failedComps));
        out.add(String.format(
                "Timeouts int %,d | jit %,d | throughput %.1f/min | score avg %.4f max %.4f",
                gs.getIntTimeouts(), gs.getJitTimeouts(), avgThroughput, gs.getAvgScore(), gs.getMaxScore()));
        out.add(String.format(
                "Exec ms int %.1f | jit %.1f | avg %.1f | compile %.1f | runtime w avg %.4f max %.4f min %.4f",
                avgIntExecMillis, avgJitExecMillis, avgCombinedExecMillis,
                avgCompilationMillis, avgRuntimeWeight, maxRuntimeWeight, minRuntimeWeight));
        long championAccepted = gs.getChampionAccepted();
        long championReplaced = gs.getChampionReplaced();
        long championRejected = gs.getChampionRejected();
        long championDiscarded = gs.getChampionDiscarded();
        long championTotal = championAccepted + championReplaced + championRejected + championDiscarded;
        out.add(String.format(
                "Champion decisions total %,d (acc %,d | repl %,d | rej %,d | disc %,d)",
                championTotal,
                championAccepted,
                championReplaced,
                championRejected,
                championDiscarded));
        out.add(String.format(
                "Queues exec %d | mutate %d | eval %d",
                execQueueSize, mutQueueSize, evalQueueSize));

        if (mutatorStats != null && mutatorStats.length > 0) {
            out.add("");
            out.add("Mutator scheduler stats:");
            MutatorType[] candidates = MutatorType.mutationCandidates();
            double[] weights = gs.getMutatorWeights(candidates);
            for (int i = 0; i < candidates.length; i++) {
                MutatorType type = candidates[i];
                double weight = (weights != null && weights.length > i) ? weights[i] : 1.0;
                GlobalStats.MutatorStats stats = mutatorStats[type.ordinal()];
                if (stats == null) {
                    continue;
                }
                double avgReward = stats.averageReward();
                long mutationTotal = stats.mutationAttemptTotal();
                long mutationSuccess = stats.mutationSuccessCount;
                long mutationSkip = stats.mutationSkipCount;
                long mutationFailure = stats.mutationFailureCount;
                long mutationCompileFailure = stats.compileFailureCount;
                double successRatePct = stats.mutationSuccessRate() * 100.0;
                long evalTotal = stats.evaluationTotal();
                double improvementRatePct = stats.evaluationImprovementRate() * 100.0;
                long evalBugs = stats.evaluationBugCount;
                long evalTimeout = stats.evaluationTimeoutCount;
                long evalFailures = stats.evaluationFailureCount;
                long evalSteady = stats.evaluationNoChangeCount;
                out.add(String.format(
                        "  %-18s w %.2f avgR %.2f | mut %d/%d (%.1f%%) skip %d fail %d comp %d | eval +%d/=%d bug %d timeout %d fail %d (tot %,d, +%.1f%%)",
                        type.name(),
                        weight,
                        avgReward,
                        mutationSuccess,
                        mutationTotal,
                        successRatePct,
                        mutationSkip,
                        mutationFailure,
                        mutationCompileFailure,
                        stats.evaluationImprovedCount,
                        evalSteady,
                        evalBugs,
                        evalTimeout,
                        evalFailures,
                        evalTotal,
                        improvementRatePct));
            }
        }

        out.add("");
        out.add("Top op frequencies:");
        if (top.isEmpty()) {
            out.add("  (no data yet)");
        } else {
            int limit = Math.min(top.size(), 12);
            int perLine = 3;
            for (int i = 0; i < limit; i += perLine) {
                StringBuilder row = new StringBuilder("  ");
                for (int j = 0; j < perLine && (i + j) < limit; j++) {
                    Map.Entry<String, Long> entry = top.get(i + j);
                    double max = Optional.ofNullable(gs.getOpMaxMap().get(entry.getKey())).orElse(0.0);
                    if (j > 0) {
                        row.append(" | ");
                    }
                    row.append(String.format("%-10s %,7d (max %.2f)",
                            entry.getKey(),
                            entry.getValue(),
                            max));
                }
                out.add(row.toString());
            }
        }
        out.add("");
        out.add("Press Ctrl+C to quit");
        return out;
    }

    private void redrawInPlace(List<String> lines) {
        if (firstDraw) {
            System.out.print("\u001B[2J\u001B[H");
            lines.forEach(System.out::println);
            firstDraw = false;
        } else {
            System.out.print("\u001B[" + lastLines + "F");
            lines.forEach(line -> System.out.print("\u001B[2K" + line + "\n"));
        }
        lastLines = lines.size();
    }

    private static boolean supportsAnsi() {
        String term = System.getenv("TERM");
        return term != null && !term.equalsIgnoreCase("dumb");
    }

    private static void hideCursor() {
        System.out.print("\u001B[?25l");
    }

    private static void restoreCursor() {
        System.out.print("\u001B[?25h");
    }

    private static String human(Duration duration) {
        long seconds = duration.getSeconds();
        long absSeconds = Math.abs(seconds);
        String positive = String.format(
                "%d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        return seconds < 0 ? "-" + positive : positive;
    }
}
