package fuzzer.runtime.scoring;

import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;

public interface Scorer {

    ScoringMode mode();

    /**
     * Compute a score preview for this test case without mutating global scoring stats.
     * The returned preview is later passed to commitScore.
     */
    ScorePreview previewScore(TestCase testCase, OptimizationVectors vectors);

    /**
     * Finalize scorer side effects (e.g. global stats updates) for a previously computed preview.
     * Implementations may assume the preview originated from this scorer.
     */
    double commitScore(TestCase testCase, ScorePreview preview);
}
