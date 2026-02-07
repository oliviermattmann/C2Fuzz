package fuzzer.runtime.monitoring;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class CorpusMetrics {
    private final LongAdder championAccepted = new LongAdder();
    private final LongAdder championReplaced = new LongAdder();
    private final LongAdder championRejected = new LongAdder();
    private final LongAdder championDiscarded = new LongAdder();
    private final AtomicLong corpusSize = new AtomicLong();

    void recordChampionAccepted() {
        championAccepted.increment();
    }

    void recordChampionReplaced() {
        championReplaced.increment();
    }

    void recordChampionRejected() {
        championRejected.increment();
    }

    void recordChampionDiscarded() {
        championDiscarded.increment();
    }

    long getChampionAccepted() {
        return championAccepted.sum();
    }

    long getChampionReplaced() {
        return championReplaced.sum();
    }

    long getChampionRejected() {
        return championRejected.sum();
    }

    long getChampionDiscarded() {
        return championDiscarded.sum();
    }

    void updateCorpusSize(long size) {
        corpusSize.set(Math.max(0L, size));
    }

    long getCorpusSize() {
        long size = corpusSize.get();
        return (size < 0L) ? 0L : size;
    }
}
