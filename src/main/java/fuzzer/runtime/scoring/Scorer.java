package fuzzer.runtime.scoring;

import fuzzer.runtime.ScoringMode;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;

public interface Scorer {

    ScoringMode mode();

    ScorePreview previewScore(TestCase testCase, OptimizationVectors vectors);

    double commitScore(TestCase testCase, ScorePreview preview);
}
