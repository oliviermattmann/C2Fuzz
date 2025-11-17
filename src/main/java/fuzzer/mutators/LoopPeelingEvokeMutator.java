package fuzzer.mutators;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class LoopPeelingEvokeMutator implements Mutator {
    Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LoopPeelingEvokeMutator.class);

    public LoopPeelingEvokeMutator(Random random) { this.random = random; }

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

        LOGGER.fine("Mutating: " + clazz.getQualifiedName());

        List<CtAssignment<?, ?>> candidates = new java.util.ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for loop-peeling candidates");
            collectAssignmentsFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting loop-peeling candidates from hot method " + hotMethod.getSimpleName());
                collectAssignments(hotMethod, ctx, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No loop-peeling candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for loop-peeling candidates");
                }
                collectAssignments(clazz, ctx, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No loop-peeling candidates in class; scanning entire model");
                collectAssignmentsFromModel(ctx, candidates);
            }
        }

        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments found for LoopPeelingEvoke");
        }

        CtAssignment<?, ?> assignment = candidates.get(random.nextInt(candidates.size()));

        long time = (System.currentTimeMillis() % 10000);
        String idxName = "i" + time;

        // int Nxxxx = 32;
        CtTypeReference<Integer> intType = factory.Type().INTEGER_PRIMITIVE;
        CtLocalVariable<Integer> nVar = factory.Code().createLocalVariable(
            intType, "N" + idxName, factory.Code().createLiteral(32)
        );
        assignment.insertBefore(nVar);

        // Build the statement to repeat (peeled) and the guarded version for inside the loop
        CtStatement peeledCore;

    
        peeledCore = assignment.clone();

        CtIf guard = factory.Core().createIf();
        guard.setCondition(factory.Code().createCodeSnippetExpression(idxName + " < 10"));
        guard.setThenStatement(assignment.clone());

        CtFor loop = makeLoop(factory, idxName, guard);
        assignment.replace(loop);

        // Peel once outside (AFTER the loop)
        loop.insertAfter(peeledCore.clone());



        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    private CtFor makeLoop(Factory factory, String idxName, CtStatement bodyStmt) {
        CtFor loop = factory.Core().createFor();
        loop.setForInit(List.of(factory.Code().createCodeSnippetStatement("int " + idxName + " = 0")));
        loop.setExpression(factory.Code().createCodeSnippetExpression(idxName + " < N" + idxName));
        loop.setForUpdate(List.of(factory.Code().createCodeSnippetStatement(idxName + "++")));
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
                    if (isLoopPeelingCandidate(assignment, ctx)) {
                        return true;
                    }
                }
            }
            for (CtElement candidate : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (isLoopPeelingCandidate(assignment, ctx)) {
                    return true;
                }
            }
        }

        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            for (CtElement candidate : c.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (isLoopPeelingCandidate(assignment, ctx)) {
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
            if (isLoopPeelingCandidate(assignment, ctx)) {
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

    private boolean isLoopPeelingCandidate(CtAssignment<?, ?> assignment, MutationContext ctx) {
        return isStandaloneAssignment(assignment) && ctx.safeToAddLoops(assignment, 1);
    }

    private boolean isStandaloneAssignment(CtAssignment<?, ?> assignment) {
        return assignment.getParent() instanceof CtBlock<?>;
    }
}
