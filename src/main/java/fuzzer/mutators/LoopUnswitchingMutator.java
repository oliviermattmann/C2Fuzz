package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class LoopUnswitchingMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LoopUnswitchingMutator.class);

    public LoopUnswitchingMutator(Random random) { this.random = random; }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        CtModel model = ctx.model();
        Factory factory = ctx.factory();

        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> hotMethod = ctx.targetMethod();
        if (clazz == null) {
            List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
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
            LOGGER.fine("Exploration mode active; scanning entire model for loop-unswitching candidates");
            collectAssignmentsFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting loop-unswitching candidates from hot method " + hotMethod.getSimpleName());
                collectAssignments(hotMethod, ctx, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No loop-unswitching candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for loop-unswitching candidates");
                }
                collectAssignments(clazz, ctx, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No loop-unswitching candidates in class; scanning entire model");
                collectAssignmentsFromModel(ctx, candidates);
            }
        }
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments found for LoopUnswitching");
        }

        CtAssignment<?, ?> assignment = candidates.get(random.nextInt(candidates.size()));

        long time = (System.currentTimeMillis() % 10000);
        String iName = "i" + time;
        String jName = "j" + time;

        // int Mxxxx = 16, Nxxxx = 32;
        CtTypeReference<Integer> intType = factory.Type().INTEGER_PRIMITIVE;
        CtLocalVariable<Integer> mVar = factory.Code().createLocalVariable(intType, "M" + time, factory.Code().createLiteral(4));
        CtLocalVariable<Integer> nVar = factory.Code().createLocalVariable(intType, "N" + time, factory.Code().createLiteral(8));
        assignment.insertBefore(mVar);
        assignment.insertBefore(nVar);

        CtStatement coreStmt = assignment.clone();

        CtSwitch<Integer> sw = buildSwitchOnI(factory, iName, coreStmt.clone());
        CtFor nested = buildNestedLoops(factory, iName, jName, time, sw);

        assignment.replace(nested);
        nested.insertAfter(coreStmt.clone());
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
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

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz) {
                for (CtElement candidate : method.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                    CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                    if (isLoopUnswitchingCandidate(assignment, ctx)) {
                        return true;
                    }
                }
            }
            for (CtElement candidate : clazz.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (isLoopUnswitchingCandidate(assignment, ctx)) {
                    return true;
                }
            }
        }

        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            for (CtElement candidate : c.getElements(e -> e instanceof CtAssignment<?, ?>)) {
                CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) candidate;
                if (isLoopUnswitchingCandidate(assignment, ctx)) {
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
            if (isLoopUnswitchingCandidate(assignment, ctx)) {
                candidates.add(assignment);
            }
        }
    }

    private void collectAssignmentsFromModel(MutationContext ctx, List<CtAssignment<?, ?>> candidates) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            collectAssignments(c, ctx, candidates);
        }
    }

    private boolean isLoopUnswitchingCandidate(CtAssignment<?, ?> assignment, MutationContext ctx) {
        return ctx.safeToAddLoops(assignment, 2) && isStandaloneAssignment(assignment) && isMutableTarget(assignment);
    }

    private boolean isStandaloneAssignment(CtAssignment<?, ?> assignment) {
        CtElement parent = assignment.getParent();
        if (!(parent instanceof CtBlock<?> block)) {
            return false;
        }
        return block.getStatements().contains(assignment);
    }

    private boolean isMutableTarget(CtAssignment<?, ?> assignment) {
        CtExpression<?> assigned = assignment.getAssigned();
        if (assigned == null) {
            return false;
        }
        if (assigned instanceof CtVariableAccess<?> varAccess) {
            CtVariable<?> decl = varAccess.getVariable().getDeclaration();
            if (decl != null && decl.hasModifier(ModifierKind.FINAL)) {
                return false;
            }
        }
        if (assigned instanceof CtFieldAccess<?> fieldAccess) {
            CtField<?> decl = fieldAccess.getVariable().getDeclaration();
            if (decl != null && decl.hasModifier(ModifierKind.FINAL)) {
                return false;
            }
            if (decl == null && fieldAccess.getVariable().isFinal()) {
                return false;
            }
        }
        return true;
    }
}
