package fuzzer.util;

import java.nio.ByteBuffer;
import java.util.Base64;

public final class NameGenerator {
    // use 2 longs so I have a 128 bit space for names
    private long high = 0L;
    private long low = 0L;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public synchronized String generateName() {
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(high)
                .putLong(low)
                .array();

        // increment counter for next name
        // if low wraps around, increment high
        low++;
        if (low == 0L) {
            high++;
        }
        return ENCODER.encodeToString(bytes);
    }
}
