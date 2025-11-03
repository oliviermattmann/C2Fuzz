package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;

public class LoopUnrollingEvokeMutator implements Mutator {
    Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LoopUnrollingEvokeMutator.class);

    public LoopUnrollingEvokeMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        CtModel model = ctx.model();
        Factory factory = ctx.factory();
        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));

        List<CtStatement> candidates = new ArrayList<>();

        for (CtElement element : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
            CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
            if (ctx.safeToAddLoops(assignment, 1)) {
                candidates.add(assignment);
            }
        }

        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates found for LoopUnrollingEvoke");
        }

        // Pick a random candidate to mutate
        CtStatement chosen = candidates.get(random.nextInt(candidates.size()));

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
        chosen.insertBefore(nVar);

        if (!(chosen instanceof CtAssignment<?, ?> assignment)) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Chosen element is not an assignment");
        }

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
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> clazz = (CtClass<?>) element;
            List<CtElement> assignments = clazz.getElements(e ->
                e instanceof CtAssignment<?, ?>
            );
            for (CtElement candidate : assignments) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (ctx.safeToAddLoops(assignment, 1)) {
                    return true;
                }
            }
        }
        return false;
    }

}
