package fuzzer.mutators;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class AlgebraicSimplificationEvoke implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(AlgebraicSimplificationEvoke.class);

    public AlgebraicSimplificationEvoke(Random random) {
        this.random = random;
    }

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


        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        // assignments whose RHS has supported binary ops
        List<CtAssignment<?, ?>> assignments = new java.util.ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for algebraic candidates");
            collectAssignmentsFromModel(ctx, assignments);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting algebraic candidates from hot method " + hotMethod.getSimpleName());
                collectAssignments(hotMethod, assignments);
            }
            if (assignments.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No algebraic candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for algebraic candidates");
                }
                collectAssignments(clazz, assignments);
            }
            if (assignments.isEmpty()) {
                LOGGER.fine("No algebraic candidates in class; scanning entire model");
                collectAssignmentsFromModel(ctx, assignments);
            }
        }
        if (assignments.isEmpty()) {
            LOGGER.fine("No candidates for algebraic simplification.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates for algebraic simplification");
        }

        CtAssignment<?, ?> chosen = assignments.get(random.nextInt(assignments.size()));

        List<CtBinaryOperator<?>> binOps = chosen.getAssignment().getElements(
            e -> e instanceof CtBinaryOperator<?> b && isSupportedKind(b.getKind())
        );
        if (binOps.isEmpty()) return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No supported binary operators found");

        CtBinaryOperator<?> target = binOps.get(random.nextInt(binOps.size()));
        CtExpression<?> repl = rewriteBinary(factory, target);
        if (repl == null) return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Failed to rewrite binary operator");

        target.replace(repl);
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && hasAssignments(method)) {
                return true;
            }
            if (hasAssignments(clazz)) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (hasAssignments(c)) {
                return true;
            }
        }
        return false;
    }



    private void collectAssignments(CtElement root, List<CtAssignment<?, ?>> assignments) {
        if (root == null) {
            return;
        }
        assignments.addAll(
            root.getElements(e -> e instanceof CtAssignment<?, ?> asg && isAlgebraicCandidate(asg))
        );
    }

    private void collectAssignmentsFromModel(MutationContext ctx, List<CtAssignment<?, ?>> assignments) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            collectAssignments((CtClass<?>) element, assignments);
        }
    }

    private boolean hasAssignments(CtElement root) {
        return root != null && !root.getElements(e -> e instanceof CtAssignment<?, ?> asg && isAlgebraicCandidate(asg)).isEmpty();
    }

    private boolean isAlgebraicCandidate(CtAssignment<?, ?> assignment) {
        return hasSupportedBinary(assignment.getAssignment());
    }

    private boolean hasSupportedBinary(CtExpression<?> e) {
        if (e == null) return false;
        return !e.getElements(x -> x instanceof CtBinaryOperator<?> b && isSupportedKind(b.getKind())).isEmpty();
    }

    private boolean isSupportedKind(BinaryOperatorKind k) {
        // all currently supported kinds of binary operators
        return switch (k) {
            case OR, AND,
                 BITOR, BITXOR, BITAND,
                 EQ, NE, LT, GT, LE, GE,
                 SL, SR, USR,
                 PLUS, MINUS, MUL, DIV, MOD,
                 INSTANCEOF -> true;
            default -> false;
        };
    }

    private CtExpression<?> rewriteBinary(Factory f, CtBinaryOperator<?> node) {
        CtExpression<?> original = node.clone();
        CtTypeReference<?> type = node.getType();
        if (type == null) {
            return null;
        }
        CtTypeReference<?> effectiveType = type;
        if (!type.isPrimitive()) {
            try {
                effectiveType = type.unbox();
            } catch (Exception ignored) {
                return null;
            }
        }
        if (effectiveType == null) {
            return null;
        }
        String typeName = effectiveType.getSimpleName();
        boolean isBoolean = "boolean".equals(typeName);
        if (!isBoolean && !"byte".equals(typeName) && !"short".equals(typeName)
                && !"char".equals(typeName) && !"int".equals(typeName)
                && !"long".equals(typeName) && !"float".equals(typeName)
                && !"double".equals(typeName)) {
            return null;
        }

        if (isBoolean) {
            CtUnaryOperator<?> not1 = f.Core().createUnaryOperator();
            not1.setKind(UnaryOperatorKind.NOT);
            not1.setOperand(original);
            CtUnaryOperator<?> not2 = f.Core().createUnaryOperator();
            not2.setKind(UnaryOperatorKind.NOT);
            not2.setOperand(not1);
            return not2;
        }

        Object zero = switch (typeName) {
            case "long" -> 0L;
            case "float" -> 0.0f;
            case "double" -> 0.0d;
            case "char" -> (char) 0;
            case "short" -> (short) 0;
            case "byte" -> (byte) 0;
            default -> 0;
        };
        CtExpression<?> zeroExpr = f.Code().createLiteral(zero);
        return f.Code().createBinaryOperator(original, zeroExpr, BinaryOperatorKind.PLUS);
    }
    // helper for getting the integer values for the shift operators
    private Integer asIntLiteral(CtExpression<?> e) {
        if (e instanceof CtLiteral<?> lit && lit.getValue() instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
