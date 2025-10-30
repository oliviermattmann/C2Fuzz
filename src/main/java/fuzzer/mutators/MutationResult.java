package fuzzer.mutators;

import spoon.Launcher;

public record MutationResult(MutationStatus status,
                             Launcher launcher,
                             String detail) {}
