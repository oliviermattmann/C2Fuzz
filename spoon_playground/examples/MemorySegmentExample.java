import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class MemorySegmentExample {
    public static void main(String[] args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment longs = arena.allocateArray(ValueLayout.JAVA_LONG, 8);

            // Write values with long-indexed loop
            for (long i = 0; i < 8; i++) {
                longs.setAtIndex(ValueLayout.JAVA_LONG, i, i * 10);
            }

            long sum = 0;
            // Read back with long index and accumulate
            for (long i = 0; i < 8; i++) {
                sum += longs.getAtIndex(ValueLayout.JAVA_LONG, i);
            }

            System.out.println("Sum = " + sum);
        }
    }
}
