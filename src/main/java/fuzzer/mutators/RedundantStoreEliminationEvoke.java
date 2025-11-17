package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;

public class RedundantStoreEliminationEvoke implements Mutator {
    private static final java.util.logging.Logger LOGGER = fuzzer.util.LoggingConfig.getLogger(RedundantStoreEliminationEvoke.class);
    Random random;
    public RedundantStoreEliminationEvoke(Random random) {
        this.random = random;
    }
    
    @Override
    public MutationResult mutate(MutationContext ctx) {
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

        LOGGER.fine(String.format("Mutating class: %s", clazz.getSimpleName()));


        List<CtStatement> candidates = new ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for redundant-store candidates");
            collectAssignmentsFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting redundant-store candidates from hot method " + hotMethod.getSimpleName());
                collectAssignments(hotMethod, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No redundant-store candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for redundant-store candidates");
                }
                collectAssignments(clazz, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No redundant-store candidates in class; scanning entire model");
                collectAssignmentsFromModel(ctx, candidates);
            }
        }

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

    private void collectAssignments(CtElement root, List<CtStatement> candidates) {
        if (root == null) {
            return;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtAssignment<?, ?>)) {
            candidates.add((CtStatement) element);
        }
    }

    private void collectAssignmentsFromModel(MutationContext ctx, List<CtStatement> candidates) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            collectAssignments((CtClass<?>) element, candidates);
        }
    }

    private boolean hasAssignments(CtElement root) {
        return root != null && !root.getElements(e -> e instanceof CtAssignment<?, ?>).isEmpty();
    }
}
