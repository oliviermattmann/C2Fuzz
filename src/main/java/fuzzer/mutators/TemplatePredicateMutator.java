package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;

public class TemplatePredicateMutator implements Mutator {
    private final Random random;

    public TemplatePredicateMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        List<CtAssignment<?, ?>> candidates = collectArrayAssignments(ctx);
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No array assignments for TAP mutator");
        }
        CtAssignment<?, ?> target = candidates.get(random.nextInt(candidates.size()));
        applyPredicateWrapper(ctx.factory(), target);
        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        return !collectArrayAssignments(ctx).isEmpty();
    }

    private List<CtAssignment<?, ?>> collectArrayAssignments(MutationContext ctx) {
        List<CtAssignment<?, ?>> result = new ArrayList<>();
        for (CtElement element : ctx.model().getElements(e -> e instanceof CtAssignment<?, ?>)) {
            CtAssignment<?, ?> assignment = (CtAssignment<?, ?>) element;
            if (assignment.getAssigned() instanceof CtArrayAccess<?, ?>) {
                result.add(assignment);
            }
        }
        return result;
    }

    private void applyPredicateWrapper(Factory factory, CtAssignment<?, ?> assignment) {
        String suffix = "tap" + Math.abs(random.nextInt(10_000));
        LocalVariableFactory varFactory = new LocalVariableFactory(factory);
        String opaqueSnippet = OpaqueToggleSupport.booleanFlipSnippet(assignment, factory);
        LocalVariableFactory.BooleanVar flagVar =
                varFactory.newBoolean("flag" + suffix, opaqueSnippet);

        CtAssignment<?, ?> thenAssign = assignment.clone();
        CtAssignment<?, ?> elseAssign = assignment.clone();

        if (elseAssign.getAssigned() instanceof CtArrayAccess<?, ?> elseAccess) {
            CtExpression<?> idx = elseAccess.getIndexExpression();
            if (idx != null) {
                CtExpression<Integer> bumped = factory.Code().createCodeSnippetExpression("(" + idx + " + 1)");
                bumped.setType(factory.Type().integerPrimitiveType());
                elseAccess.setIndexExpression(bumped);
            }
        }

        CtIf guard = factory.Core().createIf();
        guard.setCondition(read(factory, flagVar));
        guard.setThenStatement(asBlock(factory, thenAssign));
        guard.setElseStatement(asBlock(factory, elseAssign));

        CtBlock<?> wrapper = factory.Core().createBlock();
        wrapper.addStatement(flagVar.declaration());
        wrapper.addStatement(guard);

        assignment.replace(wrapper);
    }

    private CtVariableRead<Boolean> read(Factory factory, LocalVariableFactory.BooleanVar var) {
        CtVariableRead<Boolean> read = factory.Core().createVariableRead();
        read.setVariable(var.reference());
        read.setType(factory.Type().createReference(boolean.class));
        return read;
    }

    private CtStatement asBlock(Factory factory, CtAssignment<?, ?> assignment) {
        CtBlock<?> block = factory.Core().createBlock();
        block.addStatement(assignment);
        return block;
    }
}
