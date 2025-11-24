package fuzzer.util;

import java.math.BigInteger;

public final class NameGenerator {
    private static final String PREFIX = "c2fuzz";
    private static final int MIN_DIGITS = 12;
    private BigInteger counter = BigInteger.ZERO;

    public synchronized String generateName() {
        String numeric = counter.toString();
        counter = counter.add(BigInteger.ONE);
        if (numeric.length() < MIN_DIGITS) {
            StringBuilder sb = new StringBuilder(PREFIX.length() + MIN_DIGITS);
            sb.append(PREFIX);
            for (int i = numeric.length(); i < MIN_DIGITS; i++) {
                sb.append('0');
            }
            sb.append(numeric);
            return sb.toString();
        }
        return PREFIX + numeric;
    }
}
