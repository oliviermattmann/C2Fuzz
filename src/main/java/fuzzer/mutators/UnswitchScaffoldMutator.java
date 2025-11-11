package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class UnswitchScaffoldMutator implements Mutator {
    private final Random random;

    public UnswitchScaffoldMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        List<CtFor> loops = collectLoops(ctx);
        if (loops.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No for-loops to scaffold");
        }
        CtFor target = loops.get(random.nextInt(loops.size()));
        applyScaffold(ctx, target);
        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        return !collectLoops(ctx).isEmpty();
    }

    private List<CtFor> collectLoops(MutationContext ctx) {
        List<CtFor> loops = new ArrayList<>();
        for (CtElement element : ctx.model().getElements(e -> e instanceof CtFor)) {
            CtFor loop = (CtFor) element;
            if (loop.getBody() != null && loop.getExpression() != null) {
                loops.add(loop);
            }
        }
        return loops;
    }

    private void applyScaffold(MutationContext ctx, CtFor original) {
        Factory factory = ctx.factory();
        String suffix = "us" + Math.abs(random.nextInt(10_000));

        CtFor mutated = original.clone();
        CtBlock<?> origBodyBlock = ensureBlock(factory, mutated.getBody());
        CtBlock<?> fastBlock = origBodyBlock.clone();
        CtBlock<?> slowBlock = origBodyBlock.clone();
        slowBlock.addStatement(factory.Code().createCodeSnippetStatement(
                "int _unswitchMark" + suffix + " = " + "0;"));

        LocalVariableFactory varFactory = new LocalVariableFactory(factory);

        String opaqueSnippet = OpaqueToggleSupport.booleanFlipSnippet(original, factory);
        LocalVariableFactory.BooleanVar flagVar =
                varFactory.newBoolean("flag" + suffix, opaqueSnippet);
        LocalVariableFactory.IntVar limitVar = varFactory.newInt("limit" + suffix, 2);
        CtStatement warmLoop = factory.Code().createCodeSnippetStatement(
                "for (; " + limitVar.name() + " < 4; " + limitVar.name() + " *= 2) { }");
        LocalVariableFactory.IntVar zeroVar = varFactory.newInt("zero" + suffix, 34);
        CtStatement zeroLoop = factory.Code().createCodeSnippetStatement(
                "for (int peel" + suffix + " = 2; peel" + suffix + " < " + limitVar.name() + "; peel" + suffix + "++) { "
                        + zeroVar.name() + " = 0; }");

        CtBlock<?> newBody = factory.Core().createBlock();
        CtIf peelGuard = factory.Core().createIf();
        peelGuard.setCondition(read(factory, flagVar));
        CtBreak breakStmt = factory.Core().createBreak();
        peelGuard.setThenStatement(breakStmt);
        newBody.addStatement(peelGuard);

        CtIf unswitch = factory.Core().createIf();
        CtExpression<?> zeroRead = read(factory, zeroVar);
        CtBinaryOperator<Boolean> zeroCheck = factory.Core().createBinaryOperator();
        zeroCheck.setKind(BinaryOperatorKind.EQ);
        zeroCheck.setLeftHandOperand(zeroRead);
        zeroCheck.setRightHandOperand(factory.Code().createLiteral(0));
        unswitch.setCondition(zeroCheck);
        unswitch.setThenStatement(fastBlock);
        unswitch.setElseStatement(slowBlock);
        newBody.addStatement(unswitch);
        mutated.setBody(newBody);

        CtBlock<?> wrapper = factory.Core().createBlock();
        wrapper.addStatement(flagVar.declaration());
        wrapper.addStatement(limitVar.declaration());
        wrapper.addStatement(warmLoop);
        wrapper.addStatement(zeroVar.declaration());
        wrapper.addStatement(zeroLoop);
        wrapper.addStatement(mutated);

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

    private CtVariableRead<Boolean> read(Factory factory, LocalVariableFactory.BooleanVar var) {
        CtTypeReference<Boolean> type = factory.Type().createReference(boolean.class);
        CtVariableRead<Boolean> read = factory.Core().createVariableRead();
        read.setType(type);
        read.setVariable(var.reference());
        read.setImplicit(false);
        return read;
    }

    private CtVariableRead<Integer> read(Factory factory, LocalVariableFactory.IntVar var) {
        CtVariableRead<Integer> read = factory.Core().createVariableRead();
        read.setType(factory.Type().integerPrimitiveType());
        read.setVariable(var.reference());
        read.setImplicit(false);
        return read;
    }
}
