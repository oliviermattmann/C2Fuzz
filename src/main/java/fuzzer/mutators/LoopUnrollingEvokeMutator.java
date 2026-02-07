package fuzzer.mutators;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class LoopUnrollingEvokeMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LoopUnrollingEvokeMutator.class);

    public LoopUnrollingEvokeMutator(Random random) {
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

        List<CtAssignment<?, ?>> candidates = new java.util.ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for loop-unrolling candidates");
            collectAssignmentsFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting loop-unrolling candidates from hot method " + hotMethod.getSimpleName());
                collectAssignments(hotMethod, ctx, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No loop-unrolling candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for loop-unrolling candidates");
                }
                collectAssignments(clazz, ctx, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No loop-unrolling candidates in class; scanning entire model");
                collectAssignmentsFromModel(ctx, candidates);
            }
        }

        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates found for LoopUnrollingEvoke");
        }

        CtAssignment<?, ?> assignment = candidates.get(random.nextInt(candidates.size()));

        // Create unique loop variable name
        long time = (System.currentTimeMillis() % 10000);
        String idxName = "i" + time;

        CtStatement initialStatement = assignment.clone();

        CtTypeReference<Integer> intType = factory.Type().integerPrimitiveType();
        CtLocalVariable<Integer> countVar = factory.Code().createLocalVariable(
            intType,
            "N" + idxName,
            factory.Code().createLiteral(32)
        );
        countVar.addModifier(spoon.reflect.declaration.ModifierKind.FINAL);

        CtStatement loopBodyStmt = assignment.clone();
        CtFor loop = makeLoop(factory, idxName, countVar, loopBodyStmt, true);

        CtBlock<?> wrapper = factory.Core().createBlock();
        wrapper.addStatement(initialStatement);
        wrapper.addStatement(countVar);
        wrapper.addStatement(loop);

        assignment.replace(wrapper);

        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    private CtFor makeLoop(Factory factory,
                           String idxName,
                           CtLocalVariable<Integer> countVar,
                           CtStatement bodyStmt,
                           boolean startFromOne) {
        CtFor loop = factory.Core().createFor();

        int initialValue = startFromOne ? 1 : 0;
        CtLocalVariable<Integer> idxVar = factory.Code().createLocalVariable(
            factory.Type().integerPrimitiveType(),
            idxName,
            factory.Code().createLiteral(initialValue)
        );
        loop.setForInit(List.of(idxVar));

        // Condition: iX < N
        CtBinaryOperator<Boolean> condition = factory.Core().createBinaryOperator();
        condition.setLeftHandOperand(factory.Code().createVariableRead(idxVar.getReference(), false));
        condition.setRightHandOperand(factory.Code().createVariableRead(countVar.getReference(), false));
        condition.setKind(BinaryOperatorKind.LT);
        loop.setExpression(condition);

        // Update: iX++
        CtUnaryOperator<Integer> update = factory.Core().createUnaryOperator();
        update.setKind(UnaryOperatorKind.POSTINC);
        update.setOperand(factory.Code().createVariableRead(idxVar.getReference(), false));
        loop.setForUpdate(List.of(update));

        CtBlock<?> body = factory.Core().createBlock();
        body.addStatement(bodyStmt);
        loop.setBody(body);

        return loop;
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz) {
                for (CtElement candidate : method.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                    CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                    if (isLoopUnrollingCandidate(assignment, ctx)) {
                        return true;
                    }
                }
            }
            for (CtElement candidate : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (isLoopUnrollingCandidate(assignment, ctx)) {
                    return true;
                }
            }
        }

        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            for (CtElement candidate : c.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (isLoopUnrollingCandidate(assignment, ctx)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void collectAssignments(CtElement root, MutationContext ctx, List<CtAssignment<?, ?>> candidates) {
        if (root == null) {
            return;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtAssignment<?, ?>)) {
            CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
            if (isLoopUnrollingCandidate(assignment, ctx)) {
                candidates.add(assignment);
            }
        }
    }

    private void collectAssignmentsFromModel(MutationContext ctx, List<CtAssignment<?, ?>> candidates) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            collectAssignments((CtClass<?>) element, ctx, candidates);
        }
    }

    private boolean isLoopUnrollingCandidate(CtAssignment<?, ?> assignment, MutationContext ctx) {
        return ctx.safeToAddLoops(assignment, 1) && isStandaloneAssignment(assignment);
    }

    private boolean isStandaloneAssignment(CtAssignment<?, ?> assignment) {
        CtElement parent = assignment.getParent();
        if (!(parent instanceof CtBlock<?> block)) {
            return false;
        }
        return block.getStatements().contains(assignment);
    }

}
