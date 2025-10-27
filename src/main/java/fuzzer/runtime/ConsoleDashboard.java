package fuzzer.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        out.add(String.format("Tests dispatched: %,d   |   evaluated: %,d   |   successful: %,d",
                dispatched, evaluated, executed));
        out.add(String.format("Total failed compilations: %,d", failedComps));
        out.add(String.format("Total Interpreter Timeouts: %,d", gs.getIntTimeouts()));
        out.add(String.format("Total Jit Timeouts: %,d", gs.getJitTimeouts()));
        out.add(String.format("Avg throughput: %.1f tests/min", avgThroughput));
        out.add(String.format("Avg score: %.4f   |   Max score: %.4f",
                gs.getAvgScore(), gs.getMaxScore()));
        out.add(String.format("Avg exec time (int): %.1f ms   |   Avg exec time (jit): %.1f ms   |   Combined avg: %.1f ms",
                avgIntExecMillis, avgJitExecMillis, avgCombinedExecMillis));
        out.add(String.format("Avg compilation time: %.1f ms", avgCompilationMillis));
        out.add(String.format("Runtime weight: avg %.4f   |   best %.4f   |   worst %.4f",
                avgRuntimeWeight,
                maxRuntimeWeight,
                minRuntimeWeight));
        long championAccepted = gs.getChampionAccepted();
        long championReplaced = gs.getChampionReplaced();
        long championRejected = gs.getChampionRejected();
        long championDiscarded = gs.getChampionDiscarded();
        long championTotal = championAccepted + championReplaced + championRejected + championDiscarded;
        out.add(String.format(Locale.ROOT,
                "Champion decisions: total %,d | accepted %,d | replaced %,d | rejected %,d | discarded %,d",
                championTotal,
                championAccepted,
                championReplaced,
                championRejected,
                championDiscarded));
        out.add(String.format("Found bugs: %d", foundBugs));
        out.add(String.format("Execution queue size: %d   |   Mutation queue size: %d   |   Evaluation queue size: %d",
                execQueueSize, mutQueueSize, evalQueueSize));

        if (mutatorStats != null && mutatorStats.length > 0) {
            out.add("");
            out.add("Mutator weights:");
            MutatorType[] candidates = MutatorType.mutationCandidates();
            double[] weights = gs.getMutatorWeights(candidates);
            for (int i = 0; i < candidates.length; i++) {
                MutatorType type = candidates[i];
                double weight = (weights != null && weights.length > i) ? weights[i] : 0.0;
                GlobalStats.MutatorStats stats = mutatorStats[type.ordinal()];
                double avgReward = (stats != null) ? stats.averageReward() : 0.0;
                long attempts = (stats != null) ? stats.attempts : 0L;
                long timeouts = (stats != null) ? stats.timeoutCount : 0L;
                long compileFails = (stats != null) ? stats.compileFailureCount : 0L;
                out.add(String.format(Locale.ROOT,
                        "  %-30s weight %.3f | avgReward %.3f | attempts %,d | rewardSum %.3f | timeouts %,d | compFails %,d",
                        type.name(),
                        weight,
                        avgReward,
                        attempts,
                        (stats != null) ? stats.rewardSum : 0.0,
                        timeouts,
                        compileFails));
            }
        }

        out.add("");
        out.add("Top op frequencies:");
        if (top.isEmpty()) {
            out.add("  (no data yet)");
        } else {
            for (var e : top) {
                double max = Optional.ofNullable(gs.getOpMaxMap().get(e.getKey())).orElse(0.0);
                out.add(String.format("  %-12s %,10d   max: %.3f", e.getKey(), e.getValue(), max));
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
