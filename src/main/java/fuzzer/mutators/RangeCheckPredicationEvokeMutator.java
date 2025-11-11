package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.CtModel;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtVariableReference;

public class RangeCheckPredicationEvokeMutator implements Mutator {
    private static final Logger LOGGER = LoggingConfig.getLogger(RangeCheckPredicationEvokeMutator.class);
    private final Random random;

    public RangeCheckPredicationEvokeMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        Factory factory = ctx.factory();
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> hotMethod = ctx.targetMethod();
        if (clazz == null) {
            CtModel model = ctx.model();
            List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
            if (classes.isEmpty()) {
                return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
            }
            clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));
            hotMethod = null;
            LOGGER.fine("No hot class provided; selected random class " + clazz.getQualifiedName());
        }

        LOGGER.fine("Mutating class: " + clazz.getQualifiedName());

        List<RangeCheckCandidate> candidates = new ArrayList<>();
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            LOGGER.fine("Collecting range-check predication candidates from hot method " + hotMethod.getSimpleName());
            candidates.addAll(findCandidates(hotMethod));
        }
        if (candidates.isEmpty()) {
            if (hotMethod != null) {
                LOGGER.fine("No range-check predication candidates found in hot method; falling back to class scan");
            } else {
                LOGGER.fine("No hot method available; scanning entire class for range-check predication candidates");
            }
            candidates.addAll(findCandidates(clazz));
        }
        if (candidates.isEmpty()) {
            CtModel model = ctx.model();
            for (CtElement element : model.getElements(e -> e instanceof CtClass<?>)) {
                candidates.addAll(findCandidates((CtClass<?>) element));
            }
        }

        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No loops suitable for range-check predication");
        }

        RangeCheckCandidate chosen = candidates.get(random.nextInt(candidates.size()));
        applyPredication(factory, chosen);

        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && !findCandidates(method).isEmpty()) {
                return true;
            }
            if (!findCandidates(clazz).isEmpty()) {
                return true;
            }
        }
        CtModel model = ctx.model();
        for (CtElement element : model.getElements(e -> e instanceof CtClass<?>)) {
            CtClass<?> c = (CtClass<?>) element;
            if (!findCandidates(c).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void applyPredication(Factory factory, RangeCheckCandidate candidate) {
        CtExpression<Integer> guardLimit = createGuardLimit(
                factory,
                candidate.boundExpression,
                candidate.maxOffset,
                candidate.inclusiveUpperBound
        );
        String arraySnippet = "(" + candidate.arraySource + ")";
        CtExpression<Integer> arrayLength = factory.Code().createCodeSnippetExpression(arraySnippet + ".length");

        CtBinaryOperator<Boolean> guardCondition = factory.Core().createBinaryOperator();
        guardCondition.setKind(BinaryOperatorKind.GE);
        guardCondition.setLeftHandOperand(arrayLength);
        guardCondition.setRightHandOperand(guardLimit);

        CtFor fastLoop = candidate.loop.clone();
        CtFor fallbackLoop = candidate.loop.clone();

        CtIf guard = factory.Core().createIf();
        guard.setCondition(guardCondition);
        guard.setThenStatement(fastLoop);
        guard.setElseStatement(fallbackLoop);

        candidate.loop.replace(guard);
    }

    private CtExpression<Integer> createGuardLimit(Factory factory,
                                                   CtExpression<Integer> boundExpr,
                                                   int maxOffset,
                                                   boolean inclusiveUpperBound) {
        CtExpression<Integer> limit = boundExpr.clone();
        if (inclusiveUpperBound) {
            limit = addLiteral(factory, limit, 1);
        }
        if (maxOffset > 0) {
            limit = addLiteral(factory, limit, maxOffset);
        }
        return limit;
    }

    private CtExpression<Integer> addLiteral(Factory factory, CtExpression<Integer> base, int literal) {
        if (literal == 0) {
            return base;
        }
        CtBinaryOperator<Integer> sum = factory.Core().createBinaryOperator();
        sum.setKind(BinaryOperatorKind.PLUS);
        sum.setLeftHandOperand(base);
        sum.setRightHandOperand(factory.Code().createLiteral(literal));
        return sum;
    }

    private List<RangeCheckCandidate> findCandidates(CtElement scope) {
        List<RangeCheckCandidate> result = new ArrayList<>();
        if (scope == null) {
            return result;
        }
        for (CtElement element : scope.getElements(e -> e instanceof CtFor)) {
            RangeCheckCandidate candidate = analyzeLoop((CtFor) element);
            if (candidate != null) {
                result.add(candidate);
            }
        }
        return result;
    }

    private RangeCheckCandidate analyzeLoop(CtFor loop) {
        if (loop == null || loop.getBody() == null) {
            return null;
        }

        List<CtStatement> inits = loop.getForInit();
        if (inits.size() != 1) {
            return null;
        }
        InductionVariable induction = extractInductionVariable(inits.get(0));
        if (induction == null) {
            return null;
        }
        CtVariableReference<Integer> idxRef = induction.reference();

        ConditionInfo condition = extractLoopCondition(loop, idxRef);
        if (condition == null) {
            return null;
        }
        CtExpression<Integer> boundExpr = condition.boundExpression();

        List<CtStatement> updates = loop.getForUpdate();
        if (updates.size() != 1 || !isSimpleIncrement(updates.get(0), idxRef)) {
            return null;
        }

        String targetSnippet = null;
        int maxOffset = Integer.MIN_VALUE;
        for (CtElement element : loop.getBody().getElements(e -> e instanceof CtArrayAccess<?, ?>)) {
            CtArrayAccess<?, ?> access = (CtArrayAccess<?, ?>) element;
            if (!isArrayType(access)) {
                continue;
            }
            Optional<Integer> offset = extractOffset(access.getIndexExpression(), idxRef);
            if (offset.isEmpty()) {
                continue;
            }
            String snippet = buildArraySnippet(access);
            if (snippet == null) {
                continue;
            }
            if (targetSnippet == null) {
                targetSnippet = snippet;
            } else if (!targetSnippet.equals(snippet)) {
                return null;
            }
            maxOffset = Math.max(maxOffset, offset.get());
        }

        if (targetSnippet == null || maxOffset == Integer.MIN_VALUE) {
            return null;
        }

        return new RangeCheckCandidate(loop, boundExpr.clone(), targetSnippet, maxOffset, condition.inclusiveUpperBound());
    }

    private boolean isArrayType(CtArrayAccess<?, ?> access) {
        if (access == null) {
            return false;
        }
        CtExpression<?> target = access.getTarget();
        if (target == null) {
            return false;
        }
        if (target.getType() instanceof CtArrayTypeReference<?>) {
            return true;
        }
        return target.getType() != null && target.getType().isArray();
    }

    private String buildArraySnippet(CtArrayAccess<?, ?> access) {
        CtExpression<?> target = access.getTarget();
        if (target == null) {
            return null;
        }
        String snippet = target.clone().toString().trim();
        return snippet.isEmpty() ? null : snippet;
    }

    private Optional<Integer> extractOffset(CtExpression<?> indexExpr, CtVariableReference<?> idxRef) {
        if (indexExpr == null) {
            return Optional.empty();
        }
        if (isVariableRead(indexExpr, idxRef)) {
            return Optional.of(0);
        }
        if (indexExpr instanceof CtBinaryOperator<?> bin && bin.getKind() == BinaryOperatorKind.PLUS) {
            Optional<Integer> offset = literalOffset(bin.getLeftHandOperand(), bin.getRightHandOperand(), idxRef);
            if (offset.isPresent()) {
                return offset;
            }
            return literalOffset(bin.getRightHandOperand(), bin.getLeftHandOperand(), idxRef);
        }
        return Optional.empty();
    }

    private Optional<Integer> literalOffset(CtExpression<?> maybeVar, CtExpression<?> maybeLiteral, CtVariableReference<?> idxRef) {
        if (!isVariableRead(maybeVar, idxRef)) {
            return Optional.empty();
        }
        if (maybeLiteral instanceof CtLiteral<?> literal) {
            Object value = literal.getValue();
            if (value instanceof Number number) {
                int val = number.intValue();
                if (val >= 0) {
                    return Optional.of(val);
                }
            }
        }
        return Optional.empty();
    }

    private boolean isVariableRead(CtExpression<?> expression, CtVariableReference<?> idxRef) {
        return expression instanceof CtVariableRead<?> read && read.getVariable().equals(idxRef);
    }

    private ConditionInfo extractLoopCondition(CtFor loop, CtVariableReference<Integer> idxRef) {
        CtExpression<Boolean> expression = loop.getExpression();
        if (!(expression instanceof CtBinaryOperator<?> bin)) {
            return null;
        }
        BinaryOperatorKind kind = bin.getKind();
        boolean inclusive = false;
        if (kind == BinaryOperatorKind.LT) {
            inclusive = false;
        } else if (kind == BinaryOperatorKind.LE) {
            inclusive = true;
        } else {
            return null;
        }
        if (!isVariableRead(bin.getLeftHandOperand(), idxRef)) {
            return null;
        }
        if (referencesIndex(bin.getRightHandOperand(), idxRef)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        CtExpression<Integer> rhs = (CtExpression<Integer>) bin.getRightHandOperand();
        return new ConditionInfo(rhs, inclusive);
    }

    private boolean referencesIndex(CtExpression<?> expr, CtVariableReference<Integer> idxRef) {
        if (expr == null) {
            return false;
        }
        return !expr.getElements(e -> e instanceof CtVariableRead<?> read && read.getVariable().equals(idxRef)).isEmpty();
    }

    private boolean isSimpleIncrement(CtStatement statement, CtVariableReference<Integer> idxRef) {
        if (statement instanceof CtUnaryOperator<?> unary) {
            UnaryOperatorKind kind = unary.getKind();
            if (kind != UnaryOperatorKind.POSTINC && kind != UnaryOperatorKind.PREINC) {
                return false;
            }
            return isVariableRead(unary.getOperand(), idxRef);
        }
        if (statement instanceof CtAssignment<?, ?> assignment) {
            return isIncrementAssignment(assignment, idxRef);
        }
        return false;
    }

    private boolean isIncrementAssignment(CtAssignment<?, ?> assignment, CtVariableReference<Integer> idxRef) {
        if (!(assignment.getAssigned() instanceof CtVariableWrite<?> write)) {
            return false;
        }
        if (!write.getVariable().equals(idxRef)) {
            return false;
        }
        CtExpression<?> rhs = assignment.getAssignment();
        if (!(rhs instanceof CtBinaryOperator<?> bin) || bin.getKind() != BinaryOperatorKind.PLUS) {
            return false;
        }
        return (isVariableRead(bin.getLeftHandOperand(), idxRef) && isLiteralOne(bin.getRightHandOperand()))
                || (isVariableRead(bin.getRightHandOperand(), idxRef) && isLiteralOne(bin.getLeftHandOperand()));
    }

    private boolean isLiteralOne(CtExpression<?> expression) {
        return isLiteralValue(expression, 1);
    }

    private boolean isLiteralValue(CtExpression<?> expression, int expected) {
        if (expression instanceof CtLiteral<?> literal) {
            Object value = literal.getValue();
            if (value instanceof Number number) {
                return number.intValue() == expected;
            }
        }
        return false;
    }

    private InductionVariable extractInductionVariable(CtStatement initStmt) {
        if (initStmt instanceof CtLocalVariable<?> localVar) {
            if (!isIntType(localVar.getType())) {
                return null;
            }
            Integer start = literalIntValue(localVar.getDefaultExpression());
            if (start == null || start < 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            CtLocalVariable<Integer> typed = (CtLocalVariable<Integer>) localVar;
            return new InductionVariable(typed.getReference(), start);
        }
        if (initStmt instanceof CtAssignment<?, ?> assignment) {
            if (!(assignment.getAssigned() instanceof CtVariableWrite<?> write)) {
                return null;
            }
            CtVariableReference<?> ref = write.getVariable();
            if (!isIntType(ref.getType())) {
                return null;
            }
            Integer start = literalIntValue(assignment.getAssignment());
            if (start == null || start < 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            CtVariableReference<Integer> typedRef = (CtVariableReference<Integer>) ref;
            return new InductionVariable(typedRef, start);
        }
        return null;
    }

    private boolean isIntType(spoon.reflect.reference.CtTypeReference<?> type) {
        return type != null && "int".equals(type.getSimpleName());
    }

    private Integer literalIntValue(CtExpression<?> expression) {
        if (expression instanceof CtLiteral<?> literal) {
            Object value = literal.getValue();
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }

    private record RangeCheckCandidate(CtFor loop,
                                       CtExpression<Integer> boundExpression,
                                       String arraySource,
                                       int maxOffset,
                                       boolean inclusiveUpperBound) {}

    private record ConditionInfo(CtExpression<Integer> boundExpression, boolean inclusiveUpperBound) {}

    private record InductionVariable(CtVariableReference<Integer> reference, int startValue) {}
}
