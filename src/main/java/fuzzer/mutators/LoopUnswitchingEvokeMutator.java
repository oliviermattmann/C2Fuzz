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
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class LoopUnswitchingEvokeMutator implements Mutator {
    Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LoopUnswitchingEvokeMutator.class);

    public LoopUnswitchingEvokeMutator(Random random) { this.random = random; }

    @Override
    public Launcher mutate(Launcher launcher, CtModel model, Factory factory) {
        CtClass<?> clazz = (CtClass<?>) model.getElements(
            e -> e instanceof CtClass<?> ct && ct.isPublic()
        ).get(0);
        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        List<CtStatement> candidates = new ArrayList<>();
        candidates.addAll(clazz.getElements(e -> e instanceof CtAssignment<?, ?>));
        if (candidates.isEmpty()) return null;

        CtStatement chosen = candidates.get(random.nextInt(candidates.size()));

        long time = (System.currentTimeMillis() % 10000);
        String iName = "i" + time;
        String jName = "j" + time;

        // int Mxxxx = 16, Nxxxx = 32;
        CtTypeReference<Integer> intType = factory.Type().INTEGER_PRIMITIVE;
        CtLocalVariable<Integer> mVar = factory.Code().createLocalVariable(intType, "M" + time, factory.Code().createLiteral(16));
        CtLocalVariable<Integer> nVar = factory.Code().createLocalVariable(intType, "N" + time, factory.Code().createLiteral(32));
        chosen.insertBefore(mVar);
        chosen.insertBefore(nVar);

        CtStatement coreStmt;
        CtAssignment<?, ?> assignment = chosen instanceof CtAssignment<?, ?> asg ? asg : null;

        coreStmt = assignment.clone();

        CtSwitch<Integer> sw = buildSwitchOnI(factory, iName, coreStmt.clone());
        CtFor nested = buildNestedLoops(factory, iName, jName, time, sw);

        assignment.replace(nested);
        nested.insertAfter(coreStmt.clone());
        return launcher;
    }

    private CtFor buildNestedLoops(Factory factory, String iName, String jName, long time, CtStatement innerStmt) {
        // inner: for (int j = 0; j < Nxxxx; j++) { switch(i) {...} }
        CtFor inner = factory.Core().createFor();
        inner.setForInit(List.of(factory.Code().createCodeSnippetStatement("int " + jName + " = 0")));
        inner.setExpression(factory.Code().createCodeSnippetExpression(jName + " < N" + time));
        inner.setForUpdate(List.of(factory.Code().createCodeSnippetStatement(jName + "++")));
        CtBlock<?> innerBody = factory.Core().createBlock();
        innerBody.addStatement(innerStmt);
        inner.setBody(innerBody);

        // outer: for (int i = 0; i < Mxxxx; i++) { inner }
        CtFor outer = factory.Core().createFor();
        outer.setForInit(List.of(factory.Code().createCodeSnippetStatement("int " + iName + " = 0")));
        outer.setExpression(factory.Code().createCodeSnippetExpression(iName + " < M" + time));
        outer.setForUpdate(List.of(factory.Code().createCodeSnippetStatement(iName + "++")));
        CtBlock<?> outerBody = factory.Core().createBlock();
        outerBody.addStatement(inner);
        outer.setBody(outerBody);

        return outer;
    }

    private CtSwitch<Integer> buildSwitchOnI(Factory factory, String iName, CtStatement doWork) {
        CtSwitch<Integer> sw = factory.Core().createSwitch();
        // Using a snippet here is OK for the selector
        sw.setSelector(factory.Code().createCodeSnippetExpression(iName));

        CtBreak br = factory.Core().createBreak();

        CtCase<Integer> caseNeg = factory.Core().createCase();
        caseNeg.addCaseExpression(factory.Code().createLiteral(-1));
        caseNeg.addCaseExpression(factory.Code().createLiteral(-2));
        caseNeg.addCaseExpression(factory.Code().createLiteral(-3));
        caseNeg.addStatement(br);

        CtCase<Integer> caseZero = factory.Core().createCase();
        caseZero.addCaseExpression(factory.Code().createLiteral(0));
        caseZero.addStatement(doWork);

        sw.addCase(caseNeg);
        sw.addCase(caseZero);
        return sw;
    }
}
