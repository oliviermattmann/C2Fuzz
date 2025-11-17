package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;

public class LateZeroMutator implements Mutator {
    private static final Logger LOGGER = LoggingConfig.getLogger(LateZeroMutator.class);
    private final Random random;

    public LateZeroMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        List<CtAssignment<?, ?>> assignments = collectAssignments(ctx);
        if (assignments.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No assignments for LateZeroMutator");
        }
        CtAssignment<?, ?> target = assignments.get(random.nextInt(assignments.size()));
        applyLateZeroPattern(ctx, target);
        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        return !collectAssignments(ctx).isEmpty();
    }

    private List<CtAssignment<?, ?>> collectAssignments(MutationContext ctx) {
        List<CtAssignment<?, ?>> result = new ArrayList<>();
        for (CtElement element : ctx.model().getElements(e -> e instanceof CtAssignment<?, ?>)) {
            CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
            if (isStandalone(assignment) && ctx.safeToAddLoops(assignment, 2)) {
                result.add(assignment);
            }
        }
        return result;
    }

    private void applyLateZeroPattern(MutationContext ctx, CtAssignment<?, ?> assignment) {
        Factory factory = ctx.factory();
        String suffix = "lz" + Math.abs(random.nextInt(10_000));

        CtBlock<?> wrapper = factory.Core().createBlock();

        LocalVariableFactory varFactory = new LocalVariableFactory(factory);
        var limitVar = varFactory.newInt("limit" + suffix, 2);
        wrapper.addStatement(limitVar.declaration());

        CtStatement warmLoop = factory.Code().createCodeSnippetStatement(
                "for (; " + limitVar.name() + " < 4; " + limitVar.name() + " *= 2) { }");
        wrapper.addStatement(warmLoop);

        var zeroVar = varFactory.newInt("zero" + suffix, 34);
        wrapper.addStatement(zeroVar.declaration());

        String peelName = "peel" + suffix;
        CtStatement zeroLoop = factory.Code().createCodeSnippetStatement(
                "for (int " + peelName + " = 2; " + peelName + " < " + limitVar.name() + "; " + peelName + "++) { "
                        + zeroVar.name() + " = 0; }");
        wrapper.addStatement(zeroLoop);

        CtIf guard = factory.Core().createIf();
        CtBinaryOperator<Boolean> condition = factory.Core().createBinaryOperator();
        condition.setKind(BinaryOperatorKind.EQ);
        condition.setLeftHandOperand(read(factory, zeroVar));
        condition.setRightHandOperand(factory.Code().createLiteral(0));
        guard.setCondition(condition);

        guard.setThenStatement(cloneAsBlock(factory, assignment));
        guard.setElseStatement(cloneAsBlock(factory, assignment));
        wrapper.addStatement(guard);

        assignment.replace(wrapper);
        LOGGER.fine(() -> "LateZeroMutator injected zero scaffolding around assignment " + assignment.getShortRepresentation());
    }

    private CtVariableRead<Integer> read(Factory factory, LocalVariableFactory.IntVar var) {
        CtVariableRead<Integer> read = factory.Core().createVariableRead();
        read.setVariable(var.reference());
        read.setType(factory.Type().integerPrimitiveType());
        read.setImplicit(false);
        return read;
    }

    private CtStatement cloneAsBlock(Factory factory, CtAssignment<?, ?> assignment) {
        CtBlock<?> block = factory.Core().createBlock();
        block.addStatement(assignment.clone());
        return block;
    }

    private boolean isStandalone(CtAssignment<?, ?> assignment) {
        return assignment.getParent() instanceof CtBlock<?> block && block.getStatements().contains(assignment);
    }
}
