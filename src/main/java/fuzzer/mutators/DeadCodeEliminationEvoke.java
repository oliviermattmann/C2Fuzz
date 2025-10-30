package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtIf;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;

public class DeadCodeEliminationEvoke implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(DeadCodeEliminationEvoke.class);

    public DeadCodeEliminationEvoke(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        CtModel model = ctx.model();
        Factory factory = ctx.factory();
        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));


        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        // Collect all plain assignments
        List<CtAssignment<?, ?>> candidates = new ArrayList<>(
            clazz.getElements(e -> e instanceof CtAssignment)
        );
        if (candidates.isEmpty()) {
            LOGGER.warning("No assignments found for DeadCodeEliminationEvoke.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments found for DeadCodeEliminationEvoke");
        }

        // pick a random assignment
        CtAssignment<?, ?> chosen = candidates.get(random.nextInt(candidates.size()));
        CtAssignment<?, ?> deadClone = chosen.clone();

        // Build if (System.currentTimeMillis() < 0) { <clone> }
        CtIf deadIf = factory.Core().createIf();
        deadIf.setCondition(factory.Code().createCodeSnippetExpression("System.currentTimeMillis() < 0"));
        deadIf.setThenStatement(deadClone);

        // Insert the dead if before the real assignment
        chosen.insertBefore(deadIf);
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }
}
