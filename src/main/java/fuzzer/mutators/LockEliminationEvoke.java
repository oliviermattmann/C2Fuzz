package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtStatementList;
import spoon.reflect.code.CtSynchronized;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class LockEliminationEvoke implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LockEliminationEvoke.class);

    public LockEliminationEvoke(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        CtModel model = ctx.model();
        Factory factory = ctx.factory();
        // Find a public class
        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));


        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        // Collect assignment candidates
        List<CtAssignment<?, ?>> candidates = new ArrayList<>();
        for (CtElement element : clazz.getElements(e -> e instanceof CtAssignment)) {
            CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
            if (isSafeAssignment(assignment)) {
                candidates.add(assignment);
            }
        }
        if (candidates.isEmpty()) {
            LOGGER.fine("No assignments found for LockEliminationEvoke.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments found for LockEliminationEvoke");
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
            LOGGER.fine("Skipping LockEliminationEvoke: could not determine lock expression.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Could not determine lock expression");
        }

        CtSynchronized sync = factory.Core().createSynchronized();
        sync.setExpression(lockExpr);
        CtBlock<?> body = factory.Core().createBlock();
        body.addStatement(chosen.clone());
        sync.setBlock(body);

        chosen.replace(sync);
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

    @Override
    public boolean isApplicable(MutationContext ctx) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> clazz = (CtClass<?>) element;
            boolean hasCandidate = !clazz.getElements(e ->
                e instanceof CtAssignment<?, ?> assignment && isSafeAssignment((CtAssignment<?, ?>) assignment)
            ).isEmpty();
            if (hasCandidate) {
                return true;
            }
        }
        return false;
    }

}
