package fuzzer.runtime.corpus;

import java.util.Arrays;

/**
 * Lightweight array wrapper with cached hash code for use inside corpus
 * managers.
 */
final class IntArrayKey {

    private final int[] data;
    private final int hash;

    IntArrayKey(int[] counts) {
        this.data = Arrays.copyOf(counts, counts.length);
        this.hash = Arrays.hashCode(this.data);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof IntArrayKey)) {
            return false;
        }
        IntArrayKey other = (IntArrayKey) obj;
        return Arrays.equals(this.data, other.data);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
