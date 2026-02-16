package fuzzer.runtime.monitoring;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

final class SessionCounters {
    private final LongAdder totalTestsDispatched = new LongAdder();
    private final LongAdder totalTestsEvaluated = new LongAdder();
    private final LongAdder failedCompilations = new LongAdder();
    private final LongAdder foundBugs = new LongAdder();
    private final LongAdder jitTimeouts = new LongAdder();
    private final LongAdder intTimeouts = new LongAdder();
    private final LongAdder uniqueBugBuckets = new LongAdder();
    private final ConcurrentHashMap<String, Boolean> bugBucketIds = new ConcurrentHashMap<>();

    void recordTestDispatched() {
        totalTestsDispatched.increment();
    }

    void recordTestEvaluated() {
        totalTestsEvaluated.increment();
    }

    void incrementFailedCompilations() {
        failedCompilations.increment();
    }

    void incrementFoundBugs() {
        foundBugs.increment();
    }

    void incrementJitTimeouts() {
        jitTimeouts.increment();
    }

    void incrementIntTimeouts() {
        intTimeouts.increment();
    }

    boolean recordBugBucket(String bucketId) {
        if (bucketId == null || bucketId.isBlank()) {
            return false;
        }
        Boolean previous = bugBucketIds.putIfAbsent(bucketId, Boolean.TRUE);
        if (previous == null) {
            uniqueBugBuckets.increment();
            return true;
        }
        return false;
    }

    long getTotalTestsDispatched() {
        return totalTestsDispatched.sum();
    }

    long getTotalTestsEvaluated() {
        return totalTestsEvaluated.sum();
    }

    long getFailedCompilations() {
        return failedCompilations.sum();
    }

    long getFoundBugs() {
        return foundBugs.sum();
    }

    long getJitTimeouts() {
        return jitTimeouts.sum();
    }

    long getIntTimeouts() {
        return intTimeouts.sum();
    }

    long getUniqueBugBuckets() {
        return uniqueBugBuckets.sum();
    }
}
