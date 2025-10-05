package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.factory.Factory;

public class RedundantStoreEliminationEvoke implements Mutator {
    private static final java.util.logging.Logger LOGGER = fuzzer.util.LoggingConfig.getLogger(RedundantStoreEliminationEvoke.class);
    Random random;
    public RedundantStoreEliminationEvoke(Random random) {
        this.random = random;
    }
    
    @Override
    public Launcher mutate(Launcher launcher, CtModel model, Factory factory) {

        CtClass<?> clazz = (CtClass<?>) model.getElements(e -> e instanceof CtClass<?> ct && ct.isPublic()).get(0);
        LOGGER.fine(String.format("Mutating class: %s", clazz.getSimpleName()));


        List<CtStatement> candidates = new ArrayList<>();
        // Add assignments
        candidates.addAll(clazz.getElements(e -> e instanceof CtAssignment<?, ?>));

        if (candidates.isEmpty()) {
            LOGGER.fine("No candidates found for Redundant Store Elimination mutation.");
            return launcher;
        }
        
        CtStatement chosen = candidates.get(random.nextInt(candidates.size()));

        // first we clone the assignment and copy it before the chosen one
        CtAssignment<?, ?> cloned = ((CtAssignment<?, ?>) chosen).clone();
        chosen.insertBefore(cloned);

        
        return launcher;
    }

}
