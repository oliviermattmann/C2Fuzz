public final class EscapeShowcase {
    static class Point {
        int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        int norm() { return x * x + y * y; }
    }

    static int work(int iterations) {
        int sum = 0;
        for (int i = 0; i < iterations; i++) {
            // Candidate for scalar replacement: object lives entirely inside the loop
            Point p = new Point(i, i + 1);
            sum += p.norm();
        }
        return sum;
    }

    public static void main(String[] args) {
        int total = 0;
        // Make it hot enough for C2
        for (int warm = 0; warm < 200_000; warm++) {
            total += work(100);
        }
        System.out.println("Total: " + total);
    }
}
