package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;

public class RedundantStoreEliminationEvoke implements Mutator {
    private static final java.util.logging.Logger LOGGER = fuzzer.util.LoggingConfig.getLogger(RedundantStoreEliminationEvoke.class);
    Random random;
    public RedundantStoreEliminationEvoke(Random random) {
        this.random = random;
    }
    
    @Override
    public MutationResult mutate(MutationContext ctx) {
        CtModel model = ctx.model();

        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));

        LOGGER.fine(String.format("Mutating class: %s", clazz.getSimpleName()));


        List<CtStatement> candidates = new ArrayList<>();
        // Add assignments
        candidates.addAll(clazz.getElements(e -> e instanceof CtAssignment<?, ?>));

        if (candidates.isEmpty()) {
            LOGGER.fine("No candidates found for Redundant Store Elimination mutation.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates found for Redundant Store Elimination");
        }
        
        CtStatement chosen = candidates.get(random.nextInt(candidates.size()));

        // first we clone the assignment and copy it before the chosen one
        CtAssignment<?, ?> cloned = ((CtAssignment<?, ?>) chosen).clone();
        chosen.insertBefore(cloned);

        
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> clazz = (CtClass<?>) element;
            if (!clazz.getElements(e -> e instanceof CtAssignment<?, ?>).isEmpty()) {
                return true;
            }
        }
        return false;
    }

}
