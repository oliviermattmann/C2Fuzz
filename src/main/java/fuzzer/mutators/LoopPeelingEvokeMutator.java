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
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class LoopPeelingEvokeMutator implements Mutator {
    Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LoopPeelingEvokeMutator.class);

    public LoopPeelingEvokeMutator(Random random) { this.random = random; }

    @Override
    public Launcher mutate(Launcher launcher, CtModel model, Factory factory) {
        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return null;
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));

        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        List<CtStatement> candidates = new ArrayList<>();
        candidates.addAll(clazz.getElements(e -> e instanceof CtAssignment<?, ?>));

        if (candidates.isEmpty()) return null;

        CtStatement chosen = candidates.get(random.nextInt(candidates.size()));

        long time = (System.currentTimeMillis() % 10000);
        String idxName = "i" + time;

        // int Nxxxx = 32;
        CtTypeReference<Integer> intType = factory.Type().INTEGER_PRIMITIVE;
        CtLocalVariable<Integer> nVar = factory.Code().createLocalVariable(
            intType, "N" + idxName, factory.Code().createLiteral(32)
        );
        chosen.insertBefore(nVar);

        // Build the statement to repeat (peeled) and the guarded version for inside the loop
        CtStatement peeledCore;

    
        CtAssignment<?, ?> assignment = chosen instanceof CtAssignment<?, ?> asg ? asg : null;
        peeledCore = assignment.clone();

        CtIf guard = factory.Core().createIf();
        guard.setCondition(factory.Code().createCodeSnippetExpression(idxName + " < 10"));
        guard.setThenStatement(assignment.clone());

        CtFor loop = makeLoop(factory, idxName, guard);
        assignment.replace(loop);

        // Peel once outside (AFTER the loop)
        loop.insertAfter(peeledCore.clone());



        return launcher;
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

}
