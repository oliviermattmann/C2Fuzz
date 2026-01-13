package fuzzer.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages per-executor AFL shared memory and a global bitmap of seen edges.
 */
final class AflCoverageManager implements AutoCloseable {
    static final int MAP_SIZE = 2*65536;
    private static final Object GLOBAL_LOCK = new Object();
    private static final byte[] GLOBAL_BITMAP = new byte[MAP_SIZE];
    private static final AtomicLong GLOBAL_EDGE_COUNT = new AtomicLong();

    record CoverageDelta(boolean newCoverage, int newEdgeCount) {}

    private final AflSharedMemory shm;

    AflCoverageManager() {
        this.shm = AflSharedMemory.allocate(MAP_SIZE, 0600);
    }

    int shmId() {
        return shm.id();
    }

    CoverageDelta consumeAndReset() {
        byte[] local = shm.snapshot();
        boolean newCoverage = false;
        int newEdges = 0;
        synchronized (GLOBAL_LOCK) {
            for (int i = 0; i < MAP_SIZE; i++) {
                int val = local[i] & 0xFF;
                if (val == 0) {
                    continue;
                }
                if (GLOBAL_BITMAP[i] == 0) {
                    newCoverage = true;
                    newEdges++;
                }
                // OR to preserve hit info beyond binary yes/no.
                GLOBAL_BITMAP[i] |= local[i];
            }
            if (newEdges > 0) {
                GLOBAL_EDGE_COUNT.addAndGet(newEdges);
            }
        }
        shm.clear();
        return new CoverageDelta(newCoverage, newEdges);
    }

    static long totalEdges() {
        return GLOBAL_EDGE_COUNT.get();
    }

    @Override
    public void close() {
        shm.close();
    }
}
