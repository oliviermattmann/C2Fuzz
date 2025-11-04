package fuzzer.mutators;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;

public class LoopUnrollingEvokeMutator implements Mutator {
    Random random;
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
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            LOGGER.fine("Collecting loop-unrolling candidates from hot method " + hotMethod.getSimpleName());
            for (CtElement element : hotMethod.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
                if (ctx.safeToAddLoops(assignment, 1)) {
                    candidates.add(assignment);
                }
            }
        }
        if (candidates.isEmpty()) {
            if (hotMethod != null) {
                LOGGER.fine("No loop-unrolling candidates found in hot method; falling back to class scan");
            } else {
                LOGGER.fine("No hot method available; scanning entire class for loop-unrolling candidates");
            }
            for (CtElement element : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
                if (ctx.safeToAddLoops(assignment, 1)) {
                    candidates.add(assignment);
                }
            }
        }

        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates found for LoopUnrollingEvoke");
        }

        CtAssignment<?, ?> assignment = candidates.get(random.nextInt(candidates.size()));

        // Create unique loop variable name
        long time = (System.currentTimeMillis() % 10000);
        String idxName = "i" + time;

        // Create: int N = 10;
        CtTypeReference<Integer> intType = factory.Type().integerPrimitiveType();
        CtLocalVariable<Integer> nVar = factory.Code().createLocalVariable(
            intType,
            "N"+idxName,
            factory.Code().createLiteral(32)
        );
        assignment.insertBefore(nVar);

        CtFor loop = makeLoop(factory, idxName, assignment.clone());
        assignment.replace(loop);

        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    private CtFor makeLoop(Factory factory, String idxName, CtStatement bodyStmt) {
        CtFor loop = factory.Core().createFor();

        // Init: int iX = 0;
        loop.setForInit(
            List.of(factory.Code().createCodeSnippetStatement("int " + idxName + " = 0"))
        );

        // Condition: iX < N
        loop.setExpression(factory.Code().createCodeSnippetExpression(idxName + " < N" +idxName));

        // Update: iX++
        loop.setForUpdate(
            List.of(factory.Code().createCodeSnippetStatement(idxName + "++"))
        );
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
                    if (ctx.safeToAddLoops(assignment, 1)) {
                        return true;
                    }
                }
            }
            for (CtElement candidate : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (ctx.safeToAddLoops(assignment, 1)) {
                    return true;
                }
            }
        }

        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            for (CtElement candidate : c.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (ctx.safeToAddLoops(assignment, 1)) {
                    return true;
                }
            }
        }
        return false;
    }

}
