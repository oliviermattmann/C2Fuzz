package fuzzer.mutators;

import java.util.List;
import java.util.Random;

import fuzzer.util.TestCase;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtLoop;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;


public class MutationContext {
    private static final int MAX_LOOP_DEPTH = 2;

    private final Launcher launcher;
    private final CtModel model;
    private final Factory factory;
    private final Random rng;
    private final TestCase parentCase;


    public MutationContext(Launcher launcher, CtModel model, Factory factory, Random rng, TestCase parentCase) {
        this.launcher = launcher;
        this.model = model;
        this.factory = factory;
        this.rng = rng;
        this.parentCase = parentCase;

    }

    public Launcher launcher() {
        return launcher;
    }

    public CtModel model() {
        return model;
    }

    public Factory factory() {
        return factory;
    }

    public Random rng() {
        return rng;
    }

    public TestCase parentCase() {
        return parentCase;
    }

    // public CtClass<?> targetClass()   { 
    //     return targetClass; 
    // }
    // public CtMethod<?> targetMethod() { 
    //     return targetMethod; 
    // }

    public boolean safeToAddLoops(CtElement anchor, int loopsToAdd) {
        if (exceedsLoopDepth(anchor, loopsToAdd)) {
            return false;
        }
        CtMethod<?> enclosingMethod = anchor.getParent(CtMethod.class);
        if (enclosingMethod == null) {
            return true;
        }
        return callSitesWithinDepth(enclosingMethod, loopsToAdd);
    }


    public boolean exceedsLoopDepth(CtElement anchor, int loopsToAdd) {
        if (anchor == null) {
            return loopsToAdd > 0;
        }
        int depth = countEnclosingLoops(anchor);
        return depth + Math.max(loopsToAdd, 0) > MAX_LOOP_DEPTH;
    }

    private boolean callSitesWithinDepth(CtMethod<?> method, int loopsToAdd) {
        List<CtElement> invocations = model.getElements(e ->
            e instanceof spoon.reflect.code.CtInvocation<?> inv
            && inv.getExecutable() != null
            && inv.getExecutable().getExecutableDeclaration() == method
        );
        for (CtElement element : invocations) {
            if (exceedsLoopDepth(element, loopsToAdd)) {
                return false;
            }
        }
        return true;
    }

    private int countEnclosingLoops(CtElement element) {
        int depth = 0;
        CtElement current = element;
        while (current != null) {
            if (current instanceof CtLoop) {
                depth++;
            }
            current = current.getParent();
        }
        return depth;
    }

}
