package fuzzer.runtime.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import fuzzer.mutators.MutatorType;
import fuzzer.model.TestCase;
import fuzzer.model.TestCaseResult;

public final class ConsoleDashboard {
    private final GlobalStats gs;
    private boolean firstDraw = true;
    private int lastLines = 0;
    private final Instant start = Instant.now();

    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCaseResult> evaluationQueue;
    private final BlockingQueue<TestCase> executionQueue;
    private final Runnable onShutdown;
    private boolean shutdownInvoked = false;

    public ConsoleDashboard(GlobalStats gs,
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

    public void run(Duration interval) {
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
        long failedComps = gs.getFailedCompilations();

        double elapsedSeconds = Math.max(1.0, Duration.between(start, Instant.now()).toMillis() / 1000.0);
        double avgThroughput = (evaluated * 60.0) / elapsedSeconds;

        long foundBugs = gs.getFoundBugs();
        long uniqueBugs = gs.getUniqueBugBuckets();
        int mutQueueSize = mutationQueue.size();
        int execQueueSize = executionQueue.size();
        int evalQueueSize = evaluationQueue.size();
        double avgIntExecMillis = gs.getAvgIntExecTimeMillis();
        double avgJitExecMillis = gs.getAvgJitExecTimeMillis();
        double avgExecMillis = gs.getAvgExecTimeMillis();
        GlobalStats.MutatorStats[] mutatorStats = gs.snapshotMutatorStats();

        Duration up = Duration.between(start, Instant.now());
        GlobalStats.FinalMetrics fm = gs.snapshotFinalMetrics();

        out.add(String.format("FUZZER DASHBOARD  |  up %s", human(up)));
        out.add("────────────────────────────────────────────────────────────");
        out.add(String.format(
                "Tests disp %,d | eval %,d | bugs %,d (unique %,d) | failed comp %,d",
                dispatched, evaluated, foundBugs, uniqueBugs, failedComps));
        out.add(String.format(
                "Timeouts int %,d | jit %,d | throughput %.1f/min | score avg %.4f max %.4f",
                gs.getIntTimeouts(), gs.getJitTimeouts(), avgThroughput, gs.getAvgScore(), gs.getMaxScore()));
        out.add(String.format(
                "Exec ms int %.2f | jit %.2f | avg %.2f",
                avgIntExecMillis, avgJitExecMillis, avgExecMillis));
        long championAccepted = gs.getChampionAccepted();
        long championReplaced = gs.getChampionReplaced();
        long championRejected = gs.getChampionRejected();
        long championDiscarded = gs.getChampionDiscarded();
        long championTotal = championAccepted + championReplaced + championRejected + championDiscarded;
        out.add(String.format(
                "Corpus size %,d | Champion decisions %,d (acc %,d | repl %,d | rej %,d | disc %,d)",
                gs.getCorpusSize(),
                championTotal,
                championAccepted,
                championReplaced,
                championRejected,
                championDiscarded));
        out.add(String.format(
                "Coverage features %d/%d | pairs %d/%d | queues exec %d | mutate %d | eval %d",
                fm.uniqueFeatures,
                fm.totalFeatures,
                fm.uniquePairs,
                fm.totalPairs,
                execQueueSize,
                mutQueueSize,
                evalQueueSize));

        if (mutatorStats != null && mutatorStats.length > 0) {
            out.add("");
            out.add("Mutators (attempts, successes, skips, fails, compFails, bugs):");
            for (GlobalStats.MutatorStats stats : mutatorStats) {
                if (stats == null || stats.mutatorType == null || stats.mutatorType == MutatorType.SEED) {
                    continue;
                }
                long attempts = stats.mutationAttemptTotal();
                long successes = stats.mutationSuccessCount;
                long skips = stats.mutationSkipCount;
                long fails = stats.mutationFailureCount;
                long compFails = stats.compileFailureCount;
                long bugs = stats.evaluationBugCount;
                double successRate = stats.mutationSuccessRate() * 100.0;
                out.add(String.format(
                        "  %-18s attempts %,d | success %,d (%.1f%%) | skip %,d | fail %,d | compFail %,d | bug %,d",
                        stats.mutatorType.name(),
                        attempts,
                        successes,
                        successRate,
                        skips,
                        fails,
                        compFails,
                        bugs));
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
