package fuzzer.runtime.scoring;

import fuzzer.runtime.scoring.ScoringMode;
import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;

public interface Scorer {

    ScoringMode mode();

    ScorePreview previewScore(TestCase testCase, OptimizationVectors vectors);

    double commitScore(TestCase testCase, ScorePreview preview);
}
