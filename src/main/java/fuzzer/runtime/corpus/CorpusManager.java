package fuzzer.runtime.corpus;

import fuzzer.runtime.scoring.ScorePreview;
import fuzzer.util.TestCase;

/**
 * Strategy interface for seed corpus management. Each implementation decides
 * whether a newly evaluated test case should be retained, replaced, or
 * discarded and returns a {@link CorpusDecision} that drives follow-up actions
 * inside the evaluator.
 */
public interface CorpusManager {

    /**
     * Evaluate the given test case and decide how it should be integrated into
     * the corpus. Implementations may optionally use the scorer preview to
     * access PF-IDF metadata.
     */
    CorpusDecision evaluate(TestCase testCase, ScorePreview preview);

    /**
     * Current number of test cases retained in the corpus.
     */
    int corpusSize();

    /**
     * Synchronize internal bookkeeping after the external score of a champion
     * test case changed (e.g. when runtime weighting is applied).
     */
    void synchronizeChampionScore(TestCase testCase);
}
