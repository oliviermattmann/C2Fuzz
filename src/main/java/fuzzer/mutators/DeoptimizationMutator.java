package fuzzer.mutators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;

public class DeoptimizationMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(DeoptimizationMutator.class);

    public DeoptimizationMutator(Random random) {
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

        LOGGER.fine("Mutating class: " + clazz.getQualifiedName());

        List<CtAssignment<?, ?>> candidates = new ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for deoptimization candidates");
            collectAssignmentsFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting deoptimization candidates from hot method " + hotMethod.getSimpleName());
                collectAssignments(hotMethod, ctx, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No deoptimization candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for deoptimization candidates");
                }
                collectAssignments(clazz, ctx, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No deoptimization candidates in class; scanning entire model");
                collectAssignmentsFromModel(ctx, candidates);
            }
        }
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments found for DeoptimizationMutator");
        }

        CtAssignment<?, ?> chosen = candidates.get(random.nextInt(candidates.size()));

        long timestamp = System.currentTimeMillis() % 10000;
        String idxName = "i" + timestamp;
        String limitName = "N" + timestamp;
        String hotName = "object" + timestamp;

        CtBlock<?> wrapper = factory.Core().createBlock();

        CtTypeReference<Integer> intType = factory.Type().integerPrimitiveType();
        CtLocalVariable<Integer> limitVar = factory.Code().createLocalVariable(
            intType,
            limitName,
            factory.Code().createLiteral(32)
        );
        limitVar.addModifier(ModifierKind.FINAL);
        wrapper.addStatement(limitVar);

        CtTypeReference<Object> objectType = factory.Type().createReference("java.lang.Object");
        CtLocalVariable<Object> hotVar = factory.Code().createLocalVariable(
            objectType,
            hotName,
            factory.Code().createLiteral("hot")
        );
        wrapper.addStatement(hotVar);

        CtFor profilingLoop = factory.Core().createFor();

        CtLocalVariable<Integer> idxVar = factory.Code().createLocalVariable(
            intType,
            idxName,
            factory.Code().createLiteral(0)
        );
        profilingLoop.setForInit(List.of(idxVar));

        CtBinaryOperator<Boolean> loopCondition = factory.Core().createBinaryOperator();
        loopCondition.setLeftHandOperand(factory.Code().createVariableRead(idxVar.getReference(), false));
        loopCondition.setRightHandOperand(factory.Code().createVariableRead(limitVar.getReference(), false));
        loopCondition.setKind(BinaryOperatorKind.LT);
        profilingLoop.setExpression(loopCondition);

        CtUnaryOperator<Integer> loopUpdate = factory.Core().createUnaryOperator();
        loopUpdate.setKind(UnaryOperatorKind.POSTINC);
        loopUpdate.setOperand(factory.Code().createVariableRead(idxVar.getReference(), false));
        profilingLoop.setForUpdate(List.of(loopUpdate));

        CtBlock<?> loopBody = factory.Core().createBlock();
        loopBody.addStatement(createToStringInvocation(factory, factory.Code().createVariableRead(hotVar.getReference(), false)));

        CtBinaryOperator<Integer> limitMinusOne = factory.Core().createBinaryOperator();
        limitMinusOne.setLeftHandOperand(factory.Code().createVariableRead(limitVar.getReference(), false));
        limitMinusOne.setRightHandOperand(factory.Code().createLiteral(1));
        limitMinusOne.setKind(BinaryOperatorKind.MINUS);

        CtBinaryOperator<Boolean> guardCondition = factory.Core().createBinaryOperator();
        guardCondition.setLeftHandOperand(factory.Code().createVariableRead(idxVar.getReference(), false));
        guardCondition.setRightHandOperand(limitMinusOne);
        guardCondition.setKind(BinaryOperatorKind.EQ);

        CtIf guard = factory.Core().createIf();
        guard.setCondition(guardCondition);
        guard.setThenStatement(chosen.clone());
        loopBody.addStatement(guard);

        profilingLoop.setBody(loopBody);
        wrapper.addStatement(profilingLoop);

        @SuppressWarnings("unchecked")
        CtAssignment<Object, Object> hotReassign = (CtAssignment<Object, Object>) (CtAssignment<?, ?>) factory.Core().createAssignment();
        @SuppressWarnings("unchecked")
        CtExpression<Object> hotTarget = (CtExpression<Object>) (CtExpression<?>) factory.Code().createVariableRead(hotVar.getReference(), false);
        hotReassign.setAssigned(hotTarget);
        @SuppressWarnings("unchecked")
        CtExpression<Object> hotValue = (CtExpression<Object>) (CtExpression<?>) createIntegerValueOf(factory, 1);
        hotReassign.setAssignment(hotValue);
        wrapper.addStatement(hotReassign);

        wrapper.addStatement(createToStringInvocation(factory, factory.Code().createVariableRead(hotVar.getReference(), false)));

        wrapper.addStatement(chosen.clone());

        chosen.replace(wrapper);
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz) {
                for (CtElement candidate : method.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                    CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                    if (isDeoptimizationCandidate(assignment, ctx)) {
                        return true;
                    }
                }
            }
            for (CtElement candidate : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (isDeoptimizationCandidate(assignment, ctx)) {
                    return true;
                }
            }
        }

        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            for (CtElement candidate : c.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (isDeoptimizationCandidate(assignment, ctx)) {
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
            if (isDeoptimizationCandidate(assignment, ctx)) {
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

    private boolean isDeoptimizationCandidate(CtAssignment<?, ?> assignment, MutationContext ctx) {
        return ctx.safeToAddLoops(assignment, 1) && isStandaloneAssignment(assignment);
    }

    private boolean isStandaloneAssignment(CtAssignment<?, ?> assignment) {
        CtElement parent = assignment.getParent();
        if (!(parent instanceof CtBlock<?> block)) {
            return false;
        }
        return block.getStatements().contains(assignment);
    }

    private CtInvocation<?> createToStringInvocation(Factory factory, CtExpression<?> targetExpression) {
        CtTypeReference<?> objectType = factory.Type().createReference("java.lang.Object");
        CtTypeReference<?> stringType = factory.Type().createReference("java.lang.String");
        @SuppressWarnings("unchecked")
        CtExecutableReference<Object> toStringRef =
            (CtExecutableReference<Object>) (CtExecutableReference<?>) factory.Executable()
                .createReference(objectType, stringType, "toString");
        CtInvocation<Object> invocation = factory.Core().createInvocation();
        invocation.setTarget(targetExpression);
        invocation.setExecutable(toStringRef);
        invocation.setArguments(Collections.emptyList());
        invocation.setType(stringType);
        return invocation;
    }

    private CtInvocation<?> createIntegerValueOf(Factory factory, int value) {
        CtTypeReference<?> integerType = factory.Type().createReference("java.lang.Integer");
        @SuppressWarnings("unchecked")
        CtExecutableReference<Object> valueOfRef =
            (CtExecutableReference<Object>) (CtExecutableReference<?>) factory.Executable()
                .createReference(integerType, integerType, "valueOf", factory.Type().integerPrimitiveType());
        CtInvocation<Object> invocation = factory.Core().createInvocation();
        CtTypeAccess<?> target = factory.Code().createTypeAccess(integerType);
        invocation.setTarget(target);
        invocation.setExecutable(valueOfRef);
        invocation.setArguments(List.of(factory.Code().createLiteral(value)));
        invocation.setType(integerType);
        return invocation;
    }
}
