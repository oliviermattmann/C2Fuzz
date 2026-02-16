package fuzzer.runtime;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.io.FileManager;
import fuzzer.io.NameGenerator;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.TestCase;
import fuzzer.runtime.MutationAttemptEngine.MutationAttempt;
import fuzzer.runtime.corpus.CorpusManager;
import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.runtime.scheduling.MutatorScheduler;

public class MutationWorker implements Runnable {

    private static final Logger LOGGER = LoggingConfig.getLogger(MutationWorker.class);
    private static final int HISTOGRAM_LOG_INTERVAL = 10_000;
    private static final long HISTOGRAM_LOG_INTERVAL_MS = 60_000L;
    private static final AtomicLong LAST_HISTOGRAM_LOG_MS = new AtomicLong(0L);
    private static final double RANDOM_QUEUE_PICK_PROB = 0.1;

    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCase> executionQueue;
    private final int mutationBatchSize;
    private final FileManager fileManager;
    private final GlobalStats globalStats;
    private final long mutatorTimeoutMs;
    private final int mutatorSlowLimit;
    private final CorpusManager corpusManager;
    private final MutationInputSelector inputSelector;
    private final MutationAttemptEngine attemptEngine;

    private long selectionCounter = 0L;

    public MutationWorker(FileManager fm,
                          NameGenerator nameGenerator,
                          BlockingQueue<TestCase> mutationQueue,
                          BlockingQueue<TestCase> executionQueue,
                          java.util.Random random,
                          boolean printAst,
                          int minQueueCapacity,
                          double executionQueueFraction,
                          int maxExecutionQueueSize,
                          int mutationBatchSize,
                          GlobalStats globalStats,
                          MutatorScheduler scheduler,
                          long mutatorTimeoutMs,
                          int mutatorSlowLimit,
                          CorpusManager corpusManager) {
        this.fileManager = Objects.requireNonNull(fm, "fileManager");
        this.mutationQueue = Objects.requireNonNull(mutationQueue, "mutationQueue");
        this.executionQueue = Objects.requireNonNull(executionQueue, "executionQueue");
        this.globalStats = Objects.requireNonNull(globalStats, "globalStats");
        this.corpusManager = Objects.requireNonNull(corpusManager, "corpusManager");

        this.mutationBatchSize = Math.max(1, mutationBatchSize);
        this.mutatorTimeoutMs = mutatorTimeoutMs;
        this.mutatorSlowLimit = mutatorSlowLimit;

        this.inputSelector = new MutationInputSelector(
                mutationQueue,
                executionQueue,
                random,
                minQueueCapacity,
                executionQueueFraction,
                maxExecutionQueueSize,
                RANDOM_QUEUE_PICK_PROB);
        this.attemptEngine = new MutationAttemptEngine(
                fm,
                nameGenerator,
                random,
                printAst,
                globalStats,
                scheduler);
    }

    @Override
    public void run() {
        LOGGER.info("Mutator started.");
        TestCase parent = null;
        while (true) {
            try {
                if (!inputSelector.hasExecutionCapacity()) {
                    Thread.sleep(100);
                    continue;
                }

                parent = inputSelector.takeNextTestCase();
                BatchResult batchResult = runMutationBatch(parent);
                logMutationSelection(parent);
                requeueParent(parent, batchResult);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.info("Mutator interrupted; shutting down.");
                return;
            } catch (Throwable t) {
                String testcaseName = (parent != null) ? parent.getName() : "<none>";
                String mutatorName = (parent != null && parent.getMutation() != null)
                        ? parent.getMutation().name()
                        : "<unknown>";
                LOGGER.log(Level.SEVERE, String.format(
                        "Mutator loop recovered from unexpected error while mutating %s using %s",
                        testcaseName,
                        mutatorName), t);
            }
        }
    }

    private BatchResult runMutationBatch(TestCase parent) {
        for (int i = 0; i < mutationBatchSize; i++) {
            long startNs = System.nanoTime();
            MutationAttempt attempt = attemptEngine.mutate(parent);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

            if (attempt.allNotApplicable()) {
                handleUnmutableParent(parent);
                return BatchResult.normal();
            }

            TestCase mutatedTestCase = attempt.testCase();
            if (isMutationTimeout(elapsedMs)) {
                if (mutatedTestCase != null) {
                    fileManager.deleteTestCase(mutatedTestCase);
                }
                return BatchResult.slow(elapsedMs);
            }

            enqueueMutationResult(parent, mutatedTestCase);
        }
        return BatchResult.normal();
    }

    private void enqueueMutationResult(TestCase parent, TestCase mutatedTestCase) {
        if (mutatedTestCase == null) {
            LOGGER.fine("Skipping enqueue for null mutation result.");
            return;
        }
        if (!executionQueue.offer(mutatedTestCase)) {
            LOGGER.fine(() -> String.format(
                    "Offer failed while enqueuing %s; execution queue size=%d",
                    mutatedTestCase.getName(),
                    executionQueue.size()));
            fileManager.deleteTestCase(mutatedTestCase);
            return;
        }
        parent.markSelected();
    }

    private void requeueParent(TestCase parent, BatchResult batchResult) throws InterruptedException {
        if (batchResult.slowParent()) {
            boolean shouldRequeue = handleSlowParent(parent, batchResult.slowElapsedMs());
            if (shouldRequeue && parent.isActiveChampion()) {
                mutationQueue.put(parent);
            }
            return;
        }
        if (parent.isActiveChampion()) {
            mutationQueue.put(parent);
        }
    }

    private boolean isMutationTimeout(long elapsedMs) {
        return mutatorTimeoutMs > 0 && elapsedMs > mutatorTimeoutMs;
    }

    private boolean handleSlowParent(TestCase testCase, long elapsedMs) {
        int slowCount = testCase.incrementSlowMutationCount();
        int limit = (mutatorSlowLimit <= 0) ? 1 : mutatorSlowLimit;
        if (slowCount >= limit) {
            String reason = String.format("slow mutation (%d ms) %d/%d", elapsedMs, slowCount, limit);
            testCase.deactivateChampion();
            mutationQueue.remove(testCase);
            boolean removed = corpusManager.remove(testCase, reason);
            if (removed) {
                fileManager.deleteTestCase(testCase);
                globalStats.updateCorpusSize(corpusManager.corpusSize());
            }
            LOGGER.warning(String.format(
                    "Mutation of %s exceeded %d ms (elapsed %d ms); evicting after %d/%d slow mutations",
                    testCase.getName(),
                    mutatorTimeoutMs,
                    elapsedMs,
                    slowCount,
                    limit));
            return false;
        }
        LOGGER.warning(String.format(
                "Mutation of %s exceeded %d ms (elapsed %d ms); slow count %d/%d",
                testCase.getName(),
                mutatorTimeoutMs,
                elapsedMs,
                slowCount,
                limit));
        return true;
    }

    private void handleUnmutableParent(TestCase testCase) {
        String reason = "no applicable mutators";
        testCase.deactivateChampion();
        mutationQueue.remove(testCase);
        boolean removed = corpusManager.remove(testCase, reason);
        if (removed) {
            fileManager.deleteTestCase(testCase);
            globalStats.updateCorpusSize(corpusManager.corpusSize());
        }
        LOGGER.warning(String.format(
                "No applicable mutators for %s; evicting from corpus.",
                testCase.getName()));
    }

    private void logMutationSelection(TestCase testCase) {
        globalStats.recordMutationSelection(testCase.getTimesSelected());
        selectionCounter++;
        if (selectionCounter % HISTOGRAM_LOG_INTERVAL != 0) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        long lastMs = LAST_HISTOGRAM_LOG_MS.get();
        if (nowMs - lastMs < HISTOGRAM_LOG_INTERVAL_MS) {
            return;
        }
        if (!LAST_HISTOGRAM_LOG_MS.compareAndSet(lastMs, nowMs)) {
            return;
        }

        long[] histogram = globalStats.snapshotMutationSelectionHistogram();
        int maxBucket = histogram.length - 1;
        long total = 0L;
        int highestNonZero = 0;
        for (int i = 0; i < histogram.length; i++) {
            long count = histogram[i];
            total += count;
            if (count > 0) {
                highestNonZero = i;
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Mutation selection histogram (total=").append(total)
                .append(", highestBucket=").append(highestNonZero).append("): ");
        int upperBound = Math.min(highestNonZero, 10);
        for (int i = 0; i <= upperBound; i++) {
            if (i > 0) {
                summary.append(", ");
            }
            summary.append(i).append("->").append(histogram[i]);
        }
        if (highestNonZero > upperBound) {
            summary.append(", ... , ").append(maxBucket).append("+->").append(histogram[maxBucket]);
        }
        LOGGER.fine(summary.toString());
    }

    private record BatchResult(boolean slowParent, long slowElapsedMs) {
        static BatchResult normal() {
            return new BatchResult(false, 0L);
        }

        static BatchResult slow(long elapsedMs) {
            return new BatchResult(true, elapsedMs);
        }
    }
}
