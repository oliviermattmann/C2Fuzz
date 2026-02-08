package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtIf;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;

public class DeadCodeEliminationMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(DeadCodeEliminationMutator.class);

    public DeadCodeEliminationMutator(Random random) {
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
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for dead-code candidates");
            collectAssignmentsFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting dead-code candidates from hot method " + hotMethod.getSimpleName());
                collectAssignments(hotMethod, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No dead-code candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for dead-code candidates");
                }
                collectAssignments(clazz, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No dead-code candidates in class; scanning entire model");
                collectAssignmentsFromModel(ctx, candidates);
            }
        }
        if (candidates.isEmpty()) {
            LOGGER.warning("No assignments found for DeadCodeEliminationMutator.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments found for DeadCodeEliminationMutator");
        }

        // pick a random assignment
        CtAssignment<?, ?> chosen = candidates.get(random.nextInt(candidates.size()));
        CtAssignment<?, ?> deadClone = chosen.clone();

        // Build if (false) { <clone> }
        CtIf deadIf = factory.Core().createIf();
        deadIf.setCondition(factory.Code().createLiteral(false));
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
            if (method != null && method.getDeclaringType() == clazz && hasAssignments(method)) {
                return true;
            }
            if (hasAssignments(clazz)) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (hasAssignments(c)) {
                return true;
            }
        }
        return false;
    }

    private void collectAssignments(CtElement root, List<CtAssignment<?, ?>> candidates) {
        if (root == null) {
            return;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtAssignment<?, ?>)) {
            candidates.add((CtAssignment<?, ?>) element);
        }
    }

    private void collectAssignmentsFromModel(MutationContext ctx, List<CtAssignment<?, ?>> candidates) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            collectAssignments((CtClass<?>) element, candidates);
        }
    }

    private boolean hasAssignments(CtElement root) {
        return root != null && !root.getElements(e -> e instanceof CtAssignment<?, ?>).isEmpty();
    }
}
