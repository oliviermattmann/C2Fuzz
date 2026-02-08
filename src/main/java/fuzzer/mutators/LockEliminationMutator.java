package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtStatementList;
import spoon.reflect.code.CtSynchronized;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtWhile;
import spoon.reflect.code.CtDo;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class LockEliminationMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LockEliminationMutator.class);

    public LockEliminationMutator(Random random) {
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

        // Collect assignment candidates
        List<CtAssignment<?, ?>> candidates = new ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for lock-elimination candidates");
            collectAssignmentsFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting lock-elimination candidates from hot method " + hotMethod.getSimpleName());
                collectAssignments(hotMethod, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No lock-elimination candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for lock-elimination candidates");
                }
                collectAssignments(clazz, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No lock-elimination candidates in class; scanning entire model");
                collectAssignmentsFromModel(ctx, candidates);
            }
        }
        if (candidates.isEmpty()) {
            LOGGER.fine("No assignments found for LockEliminationMutator.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments found for LockEliminationMutator");
        }


        CtAssignment<?, ?> chosen = candidates.get(random.nextInt(candidates.size()));
        CtMethod<?> parentMethod = chosen.getParent(CtMethod.class);
        boolean inStaticContext = parentMethod != null && parentMethod.isStatic();

        CtExpression<?> lockExpr;
        if (inStaticContext) {
            lockExpr = makeClassLiteral(factory, clazz.getReference());
        }
        else {
            lockExpr = factory.Code().createThisAccess((CtTypeReference) clazz.getReference());
        }
        if (lockExpr == null) {
            LOGGER.fine("Skipping LockEliminationMutator: could not determine lock expression.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Could not determine lock expression");
        }

        CtBlock<?> targetBlock = chosen.getParent(CtBlock.class);
        if (targetBlock != null && canWrapBlock(targetBlock)) {
            wrapWholeBlock(factory, targetBlock, lockExpr.clone());
        } else {
            CtSynchronized sync = factory.Core().createSynchronized();
            sync.setExpression(lockExpr.clone());
            CtBlock<?> body = factory.Core().createBlock();
            body.addStatement(chosen.clone());
            sync.setBlock(body);
            chosen.replace(sync);
        }
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    private static CtExpression<?> makeClassLiteral(Factory f, CtTypeReference<?> tref) {
        // Try Spoon convenience (present in newer Spoon)
        try {
            return (CtExpression<?>) f.Code().getClass()
                .getMethod("createClassAccess", CtTypeReference.class)
                .invoke(f.Code(), tref);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to use createClassAccess, falling back to createTypeAccess", e);
            return f.Code().createTypeAccess(tref);
        }

    }

    private boolean isSafeAssignment(CtAssignment<?, ?> assignment) {
        if (assignment == null) {
            return false;
        }

        CtFor parentFor = assignment.getParent(CtFor.class);
        if (parentFor != null) {
            if (parentFor.getForInit().contains(assignment) || parentFor.getForUpdate().contains(assignment)) {
                return false;
            }
        }

        CtBlock<?> parentBlock = assignment.getParent(CtBlock.class);
        CtStatementList statementList = assignment.getParent(CtStatementList.class);
        return parentBlock != null || statementList != null;
    }

    private void collectAssignments(CtElement root, List<CtAssignment<?, ?>> candidates) {
        if (root == null) {
            return;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtAssignment<?, ?>)) {
            CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
            if (isSafeAssignment(assignment)) {
                candidates.add(assignment);
            }
        }
    }

    private void collectAssignmentsFromModel(MutationContext ctx, List<CtAssignment<?, ?>> candidates) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            collectAssignments((CtClass<?>) element, candidates);
        }
    }

    private boolean hasSafeAssignment(CtElement root) {
        return root != null && !root.getElements(e ->
            e instanceof CtAssignment<?, ?> assignment && isSafeAssignment((CtAssignment<?, ?>) assignment)
        ).isEmpty();
    }

    private boolean canWrapBlock(CtBlock<?> block) {
        CtElement parent = block.getParent();
        return parent != null && !(parent instanceof CtFor) && !(parent instanceof CtWhile) && !(parent instanceof CtDo);
    }

    private void wrapWholeBlock(Factory factory, CtBlock<?> block, CtExpression<?> lockExpr) {
        CtSynchronized sync = factory.Core().createSynchronized();
        sync.setExpression(lockExpr);
        CtBlock<?> syncBody = factory.Core().createBlock();
        java.util.List<CtStatement> original = new java.util.ArrayList<>(block.getStatements());
        block.getStatements().clear();
        for (CtStatement stmt : original) {
            syncBody.addStatement(stmt);
        }
        sync.setBlock(syncBody);
        block.addStatement(sync);
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && hasSafeAssignment(method)) {
                return true;
            }
            if (hasSafeAssignment(clazz)) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (hasSafeAssignment(c)) {
                return true;
            }
        }
        return false;
    }

}
