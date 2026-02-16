package fuzzer.runtime.corpus;

import java.util.List;
import java.util.Objects;

import fuzzer.model.TestCase;

/**
 * Result of a {@link CorpusManager} decision. Mirrors the legacy champion
 * outcomes so existing evaluator logic can remain unchanged.
 */
public final class CorpusDecision {

    public enum Outcome {
        ACCEPTED,
        REPLACED,
        REJECTED,
        DISCARDED
    }

    private final Outcome outcome;
    private final TestCase previousChampion;
    private final List<TestCase> evictedChampions;
    private final String reason;

    private CorpusDecision(Outcome outcome, TestCase previousChampion, List<TestCase> evictedChampions, String reason) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.previousChampion = previousChampion;
        this.evictedChampions = evictedChampions != null ? List.copyOf(evictedChampions) : List.of();
        this.reason = reason;
    }

    public static CorpusDecision accepted(List<TestCase> evicted) {
        return new CorpusDecision(Outcome.ACCEPTED, null, evicted, null);
    }

    public static CorpusDecision replaced(TestCase previousChampion, List<TestCase> evicted) {
        return new CorpusDecision(Outcome.REPLACED, previousChampion, evicted, null);
    }

    public static CorpusDecision rejected(TestCase incumbent, String reason) {
        return new CorpusDecision(Outcome.REJECTED, incumbent, List.of(), reason);
    }

    public static CorpusDecision discarded(String reason) {
        return new CorpusDecision(Outcome.DISCARDED, null, List.of(), reason);
    }

    public Outcome outcome() {
        return outcome;
    }

    public TestCase previousChampion() {
        return previousChampion;
    }

    public List<TestCase> evictedChampions() {
        return evictedChampions;
    }

    public String reason() {
        return reason;
    }
}
