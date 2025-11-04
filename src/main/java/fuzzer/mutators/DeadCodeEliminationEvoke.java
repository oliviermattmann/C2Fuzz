package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtIf;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;

public class DeadCodeEliminationEvoke implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(DeadCodeEliminationEvoke.class);

    public DeadCodeEliminationEvoke(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        Factory factory = ctx.factory();
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> hotMethod = ctx.targetMethod();
        if (clazz == null) {
            List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
            if (classes.isEmpty()) {
                return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
            }
            clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));
            hotMethod = null;
            LOGGER.fine("No hot class provided; selected random class " + clazz.getQualifiedName());
        }


        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        // Collect all plain assignments
        List<CtAssignment<?, ?>> candidates = new ArrayList<>();
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            LOGGER.fine("Collecting dead-code candidates from hot method " + hotMethod.getSimpleName());
            for (CtElement element : hotMethod.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                candidates.add((CtAssignment<?, ?>) element);
            }
        }
        if (candidates.isEmpty()) {
            if (hotMethod != null) {
                LOGGER.fine("No dead-code candidates found in hot method; falling back to class scan");
            } else {
                LOGGER.fine("No hot method available; scanning entire class for dead-code candidates");
            }
            for (CtElement element : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                candidates.add((CtAssignment<?, ?>) element);
            }
        }
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

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz) {
                boolean methodHasAssignment = !method.getElements(e -> e instanceof CtAssignment<?, ?>).isEmpty();
                if (methodHasAssignment) {
                    return true;
                }
            }
            boolean classHasAssignment = !clazz.getElements(e -> e instanceof CtAssignment<?, ?>).isEmpty();
            if (classHasAssignment) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (!c.getElements(e -> e instanceof CtAssignment<?, ?>).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
