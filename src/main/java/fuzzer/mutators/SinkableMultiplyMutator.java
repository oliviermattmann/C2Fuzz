package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtElement;
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
        return !collectInnerLoops(ctx).isEmpty();
    }

    private List<CtFor> collectInnerLoops(MutationContext ctx) {
        List<CtFor> result = new ArrayList<>();
        for (CtElement element : ctx.model().getElements(e -> e instanceof CtFor)) {
            CtFor loop = (CtFor) element;
            if (loop.getParent(CtLoop.class) != null) {
                result.add(loop);
            }
        }
        return result;
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
