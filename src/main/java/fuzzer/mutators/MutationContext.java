package fuzzer.mutators;

import java.util.Random;

import fuzzer.util.TestCase;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.factory.Factory;

public record MutationContext(
    Launcher launcher,
    CtModel model,
    Factory factory,
    Random rng,
    TestCase parentCase
) {}