package fuzzer.runtime;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.BlockingQueue;

import fuzzer.model.TestCase;

final class MutationInputSelector {

    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCase> executionQueue;
    private final Random random;
    private final int minQueueCapacity;
    private final double executionQueueFraction;
    private final int maxExecutionQueueSize;
    private final double randomQueuePickProb;

    MutationInputSelector(
            BlockingQueue<TestCase> mutationQueue,
            BlockingQueue<TestCase> executionQueue,
            Random random,
            int minQueueCapacity,
            double executionQueueFraction,
            int maxExecutionQueueSize,
            double randomQueuePickProb) {
        this.mutationQueue = Objects.requireNonNull(mutationQueue, "mutationQueue");
        this.executionQueue = Objects.requireNonNull(executionQueue, "executionQueue");
        this.random = Objects.requireNonNull(random, "random");
        this.minQueueCapacity = minQueueCapacity;
        this.executionQueueFraction = executionQueueFraction;
        this.maxExecutionQueueSize = maxExecutionQueueSize;
        this.randomQueuePickProb = randomQueuePickProb;
    }

    boolean hasExecutionCapacity() {
        if (maxExecutionQueueSize > 0 && executionQueue.size() >= maxExecutionQueueSize) {
            return false;
        }
        int dynamicLimit = Math.max(0, minQueueCapacity);
        if (executionQueueFraction > 0.0) {
            int byFraction = (int) Math.ceil(mutationQueue.size() * executionQueueFraction);
            dynamicLimit = Math.max(dynamicLimit, byFraction);
        }
        if (dynamicLimit <= 0) {
            return true;
        }
        return executionQueue.size() < dynamicLimit;
    }

    TestCase takeNextTestCase() throws InterruptedException {
        if (random.nextDouble() < randomQueuePickProb) {
            TestCase randomCandidate = tryPickRandomFromQueue();
            if (randomCandidate != null) {
                return randomCandidate;
            }
        }
        return mutationQueue.take();
    }

    private TestCase tryPickRandomFromQueue() {
        if (mutationQueue.size() <= 1) {
            return null;
        }
        TestCase[] snapshot = mutationQueue.toArray(new TestCase[0]);
        if (snapshot.length == 0) {
            return null;
        }
        TestCase candidate = snapshot[random.nextInt(snapshot.length)];
        if (candidate != null && mutationQueue.remove(candidate)) {
            return candidate;
        }
        return null;
    }
}
