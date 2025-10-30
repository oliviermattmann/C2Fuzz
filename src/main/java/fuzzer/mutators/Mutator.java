package fuzzer.mutators;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.factory.Factory;


public interface Mutator {
    // Define mutation methods here
    MutationResult mutate(MutationContext ctx);
}
