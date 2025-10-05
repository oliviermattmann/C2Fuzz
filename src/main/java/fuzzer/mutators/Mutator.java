package fuzzer.mutators;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.factory.Factory;


public interface Mutator {
    // Define mutation methods here
    Launcher mutate(Launcher launcher, CtModel model, Factory factory);
}
