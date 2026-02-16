package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;

public class SinkableMultiplyMutator implements Mutator {
    private final Random random;

    public SinkableMultiplyMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        List<CtFor> candidates = collectInnerLoops(ctx);
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No nested loops to mutate");
        }
        CtFor target = candidates.get(random.nextInt(candidates.size()));
        applySinkPattern(ctx.factory(), target);
        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        return hasInnerLoops(ctx);
    }

    private boolean hasInnerLoops(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && hasInnerLoopsInElement(method)) {
                return true;
            }
            if (hasInnerLoopsInElement(clazz)) {
                return true;
            }
        }
        return hasInnerLoopsInModel(ctx);
    }

    private boolean hasInnerLoopsInElement(CtElement root) {
        if (root == null) {
            return false;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtFor)) {
            CtFor loop = (CtFor) element;
            if (loop.getParent(CtLoop.class) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInnerLoopsInModel(MutationContext ctx) {
        for (CtElement element : ctx.model().getElements(e -> e instanceof CtFor)) {
            CtFor loop = (CtFor) element;
            if (loop.getParent(CtLoop.class) != null) {
                return true;
            }
        }
        return false;
    }

    private List<CtFor> collectInnerLoops(MutationContext ctx) {
        List<CtFor> result = new ArrayList<>();
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> hotMethod = ctx.targetMethod();
        if (clazz == null) {
            List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
            if (!classes.isEmpty()) {
                clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));
                hotMethod = null;
            }
        }
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            collectInnerLoopsFromModel(ctx, result);
            return result;
        }
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            collectInnerLoopsFromElement(hotMethod, result);
        }
        if (result.isEmpty() && clazz != null) {
            collectInnerLoopsFromElement(clazz, result);
        }
        if (result.isEmpty()) {
            collectInnerLoopsFromModel(ctx, result);
        }
        return result;
    }

    private void collectInnerLoopsFromElement(CtElement root, List<CtFor> result) {
        if (root == null) {
            return;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtFor)) {
            CtFor loop = (CtFor) element;
            if (loop.getParent(CtLoop.class) != null) {
                result.add(loop);
            }
        }
    }

    private void collectInnerLoopsFromModel(MutationContext ctx, List<CtFor> result) {
        for (CtElement element : ctx.model().getElements(e -> e instanceof CtFor)) {
            CtFor loop = (CtFor) element;
            if (loop.getParent(CtLoop.class) != null) {
                result.add(loop);
            }
        }
    }

    private void applySinkPattern(Factory factory, CtFor original) {
        CtFor mutated = original.clone();
        CtBlock<?> body = ensureBlock(factory, mutated.getBody());
        String suffix = "snk" + Math.abs(random.nextInt(10_000));
        String yVar = "y" + suffix;
        String sinkVar = "toSink" + suffix;

        body.insertBegin(factory.Code().createCodeSnippetStatement(yVar + "++;"));
        body.addStatement(factory.Code().createCodeSnippetStatement(
                "try { " + sinkVar + " = 23 * (" + yVar + " - 1); } catch (ArithmeticException ex) { "
                        + sinkVar + " ^= ex.hashCode(); }"));
        mutated.setBody(body);

        CtBlock<?> wrapper = factory.Core().createBlock();
        wrapper.addStatement(factory.Code().createCodeSnippetStatement("int " + yVar + " = 0;"));
        wrapper.addStatement(factory.Code().createCodeSnippetStatement("int " + sinkVar + " = 0;"));
        wrapper.addStatement(mutated);
        wrapper.addStatement(factory.Code().createCodeSnippetStatement("int _sinkUse" + suffix + " = " + sinkVar + ";"));

        original.replace(wrapper);
    }

    private CtBlock<?> ensureBlock(Factory factory, CtStatement statement) {
        if (statement instanceof CtBlock<?>) {
            return (CtBlock<?>) statement;
        }
        CtBlock<?> block = factory.Core().createBlock();
        block.addStatement(statement);
        return block;
    }
}
