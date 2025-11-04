package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class DeoptimizationEvoke implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(DeoptimizationEvoke.class);

    public DeoptimizationEvoke(Random random) {
        this.random = random;
    }

    // TODO rework this mutator to be less of a hack, feel like it could be better implemented (not sure if it even works correctly)

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
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            LOGGER.fine("Collecting deoptimization candidates from hot method " + hotMethod.getSimpleName());
            for (CtElement element : hotMethod.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
                if (ctx.safeToAddLoops(assignment, 1)) {
                    candidates.add(assignment);
                }
            }
        }
        if (candidates.isEmpty()) {
            if (hotMethod != null) {
                LOGGER.fine("No deoptimization candidates found in hot method; falling back to class scan");
            } else {
                LOGGER.fine("No hot method available; scanning entire class for deoptimization candidates");
            }
            for (CtElement element : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
                if (ctx.safeToAddLoops(assignment, 1)) {
                    candidates.add(assignment);
                }
            }
        }
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments found for DeoptimizationEvoke");
        }

        CtAssignment<?, ?> chosen = candidates.get(random.nextInt(candidates.size()));

        // Create unique names for loop variable and hot object
        int time = (int) (System.currentTimeMillis() % 10000);
        String idxName = "i" + time;
        String limitName = "N" + time;
        String hotName = "object" + time;

        // create an N constant: int NXXX = 256;
        CtTypeReference<Integer> intType = factory.Type().createReference(int.class);
        CtStatement nVar = factory.Code().createLocalVariable(intType, limitName, factory.Code().createLiteral(32));
        chosen.insertBefore(nVar);

        // create 'Object hotObjXXX = "hot";' as a snippet (safer than trying to construct typed AST here)
        CtStatement hotDecl = factory.Code().createCodeSnippetStatement("Object " + hotName + " = \"hot\";");
        chosen.insertBefore(hotDecl);

        // Build if (i == N - 1) { <original assignment> }
        CtIf ifStmt = factory.Core().createIf();
        ifStmt.setCondition(factory.Code().createCodeSnippetExpression(idxName + " == N" + time + " - 1"));
        ifStmt.setThenStatement(chosen.clone());

        // Build loop:
        // for (int iXXX = 0; iXXX < NXXX; iXXX++) {
        //    hotObjXXX.toString();
        //    if (iXXX == NXXX - 1) { <chosen clone> }
        // }
        CtFor loop = factory.Core().createFor();
        loop.setForInit(List.of(factory.Code().createCodeSnippetStatement("int " + idxName + " = 0")));
        loop.setExpression(factory.Code().createCodeSnippetExpression(idxName + " < N" + time));
        loop.setForUpdate(List.of(factory.Code().createCodeSnippetStatement(idxName + "++")));
        CtBlock<?> body = factory.Core().createBlock();
        // add a hot virtual call that the JIT can profile
        body.addStatement(factory.Code().createCodeSnippetStatement(hotName + ".toString();"));
        // add the guarded original assignment into the loop body
        body.addStatement(ifStmt);
        loop.setBody(body);

        // Replace the original assignment with the loop
        chosen.replace(loop);

        // After loop: change the type of hotObj and do the call once more to break the assumption:
        // hotObjXXX = Integer.valueOf(1);
        // hotObjXXX.toString();
        CtStatement reassign = factory.Code().createCodeSnippetStatement(hotName + " = Integer.valueOf(1);");
        CtStatement doCallAfter = factory.Code().createCodeSnippetStatement(hotName + ".toString();");

        loop.insertAfter(reassign);
        loop.insertAfter(doCallAfter);

        // Also execute the original assignment once more after the loop (mirrors evoke pattern)
        loop.insertAfter(chosen.clone());
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
