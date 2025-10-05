package fuzzer;

import java.util.List;
import java.util.Map;

public class Features {
    static double[] buildFeatureVector(Map<String, Integer> optCounts, Map<String, Double> graphHistogram, List<String> optOrder, List<String> nodeOrder) {
        // For now ignore node features because I need a vector with the same dimenstions
        double[] vec = new double[optOrder.size() + graphHistogram.size()];
        int idx = 0;
        for (String opt : optOrder) {
            vec[idx++] = optCounts.getOrDefault(opt, 0);
        }
        for (String node : nodeOrder) {
            vec[idx++] = graphHistogram.getOrDefault(node, 0.0);
        }
        return vec;
    }

    static Map<String, Double> normalizeOptCounts(Map<String, Integer> optCounts) {
        int total = optCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return Map.of();
        }
        return optCounts.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue() / (double) total
                ));
    }
    

    static double computeCosinseDistance(double[] first, double[] second) {
        // TODO need to make sure that both vectors are of the same size
        double dotProduct = 0.0;
        double firstMagnitude = 0.0;
        double secondMagnitude = 0.0;

        for (int i = 0; i < first.length; i++) {
            dotProduct += first[i] * second[i];
            firstMagnitude += first[i] * first[i];
            secondMagnitude += second[i] * second[i];
        }

        if (firstMagnitude == 0 || secondMagnitude == 0) {
            return 0.0;
        }
        double cosineDisance = 1 - (dotProduct / (Math.sqrt(firstMagnitude) * Math.sqrt(secondMagnitude)));
        return cosineDisance;
    }
}
