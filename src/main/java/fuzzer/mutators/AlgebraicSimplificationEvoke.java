package fuzzer.mutators;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
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
        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));


        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        // assignments whose RHS has supported binary ops
        List<CtAssignment<?, ?>> assignments = clazz.getElements(
            e -> e instanceof CtAssignment<?, ?> asg && hasSupportedBinary(asg.getAssignment())
        );
        if (assignments.isEmpty()) {
            LOGGER.fine("No candidates for algebraic simplification.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates for algebraic simplification");
        }

        CtAssignment<?, ?> chosen = assignments.get(random.nextInt(assignments.size()));

        // gather nested ops
        List<CtBinaryOperator<?>> binOps = chosen.getAssignment().getElements(
            e -> e instanceof CtBinaryOperator<?> b && isSupportedKind(b.getKind())
        );
        if (binOps.isEmpty()) return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No supported binary operators found");

        CtBinaryOperator<?> target = binOps.get(random.nextInt(binOps.size()));
        BinaryOperatorKind kind = target.getKind();

        // declare N if needed
        String nName = null;
        if (needsN(kind)) {
            int N = Math.max(1, random.nextInt(50));
            int suffix = (int) (System.currentTimeMillis() % 10000);
            nName = "N" + suffix;
            CtTypeReference<Integer> intType = factory.Type().integerPrimitiveType();
            CtStatement nDecl = factory.Code().createLocalVariable(intType, nName, factory.Code().createLiteral(N));
            chosen.insertBefore(nDecl);
        }

        CtExpression<?> repl = rewriteBinary(factory, target, nName);
        if (repl == null) return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Failed to rewrite binary operator");

        target.replace(repl);
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
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

    private boolean needsN(BinaryOperatorKind k) {
        // these cases need an additional variable N to complicate the expression
        return switch (k) {
            case PLUS, MINUS, MUL, DIV -> true;
            default -> false;
        };
    }

    private CtExpression<?> rewriteBinary(Factory f, CtBinaryOperator<?> node, String nName) {
        BinaryOperatorKind k = node.getKind();
        CtExpression<?> L = node.getLeftHandOperand().clone();
        CtExpression<?> R = node.getRightHandOperand().clone();

        switch (k) {
            // +
            case PLUS -> {
                CtExpression<?> N = f.Code().createCodeSnippetExpression(nName);
                return f.Code().createBinaryOperator(
                    f.Code().createBinaryOperator(L, N, BinaryOperatorKind.MINUS),
                    f.Code().createBinaryOperator(N.clone(), R, BinaryOperatorKind.PLUS),
                    BinaryOperatorKind.PLUS
                );
            }
            // -
            case MINUS -> {
                CtExpression<?> N = f.Code().createCodeSnippetExpression(nName);
                if (random.nextBoolean()) {
                    return f.Code().createBinaryOperator(
                        f.Code().createBinaryOperator(L, N, BinaryOperatorKind.MINUS),
                        f.Code().createBinaryOperator(R, N.clone(), BinaryOperatorKind.MINUS),
                        BinaryOperatorKind.MINUS
                    );
                } else {
                    return f.Code().createBinaryOperator(
                        f.Code().createBinaryOperator(L, N, BinaryOperatorKind.PLUS),
                        f.Code().createBinaryOperator(R, N.clone(), BinaryOperatorKind.PLUS),
                        BinaryOperatorKind.MINUS
                    );
                }
            }
            // *
            case MUL -> {
                CtExpression<?> N = f.Code().createCodeSnippetExpression(nName);
                CtBinaryOperator<?> leftTerm = f.Code().createBinaryOperator(
                    f.Code().createBinaryOperator(L, N, BinaryOperatorKind.MINUS),
                    R,
                    BinaryOperatorKind.MUL
                );
                CtBinaryOperator<?> rightTerm = f.Code().createBinaryOperator(R.clone(), N.clone(), BinaryOperatorKind.MUL);
                return f.Code().createBinaryOperator(leftTerm, rightTerm, BinaryOperatorKind.PLUS);
            }
            // /
            case DIV -> {
                CtExpression<?> N = f.Code().createCodeSnippetExpression(nName);
                CtBinaryOperator<?> top = f.Code().createBinaryOperator(L, f.Code().createBinaryOperator(R, N, BinaryOperatorKind.MUL), BinaryOperatorKind.PLUS);
                CtBinaryOperator<?> div = f.Code().createBinaryOperator(top, R.clone(), BinaryOperatorKind.DIV);
                return f.Code().createBinaryOperator(div, N.clone(), BinaryOperatorKind.MINUS);
            }
            // %
            case MOD -> {
                CtBinaryOperator<?> div = f.Code().createBinaryOperator(L.clone(), R.clone(), BinaryOperatorKind.DIV);
                CtBinaryOperator<?> mult = f.Code().createBinaryOperator(div, R.clone(), BinaryOperatorKind.MUL);
                return f.Code().createBinaryOperator(L, mult, BinaryOperatorKind.MINUS);
            }
            // &
            case BITAND -> {
                CtUnaryOperator<?> notA = f.Core().createUnaryOperator(); notA.setKind(UnaryOperatorKind.COMPL); notA.setOperand(L);
                CtUnaryOperator<?> notB = f.Core().createUnaryOperator(); notB.setKind(UnaryOperatorKind.COMPL); notB.setOperand(R);
                CtBinaryOperator<?> or = f.Code().createBinaryOperator(notA, notB, BinaryOperatorKind.BITOR);
                CtUnaryOperator<?> notAll = f.Core().createUnaryOperator(); notAll.setKind(UnaryOperatorKind.COMPL); notAll.setOperand(or);
                return notAll;
            }
            // |
            case BITOR -> {
                CtUnaryOperator<?> notA = f.Core().createUnaryOperator(); notA.setKind(UnaryOperatorKind.COMPL); notA.setOperand(L);
                CtUnaryOperator<?> notB = f.Core().createUnaryOperator(); notB.setKind(UnaryOperatorKind.COMPL); notB.setOperand(R);
                CtBinaryOperator<?> and = f.Code().createBinaryOperator(notA, notB, BinaryOperatorKind.BITAND);
                CtUnaryOperator<?> notAll = f.Core().createUnaryOperator(); notAll.setKind(UnaryOperatorKind.COMPL); notAll.setOperand(and);
                return notAll;
            }
            // ^
            case BITXOR -> {
                CtBinaryOperator<?> or = f.Code().createBinaryOperator(L, R, BinaryOperatorKind.BITOR);
                CtBinaryOperator<?> and = f.Code().createBinaryOperator(L.clone(), R.clone(), BinaryOperatorKind.BITAND);
                CtUnaryOperator<?> notAnd = f.Core().createUnaryOperator(); notAnd.setKind(UnaryOperatorKind.COMPL); notAnd.setOperand(and);
                return f.Code().createBinaryOperator(or, notAnd, BinaryOperatorKind.BITAND);
            }
            // &&
            case AND -> {
                CtUnaryOperator<?> notA = f.Core().createUnaryOperator(); notA.setKind(UnaryOperatorKind.NOT); notA.setOperand(L);
                CtUnaryOperator<?> notB = f.Core().createUnaryOperator(); notB.setKind(UnaryOperatorKind.NOT); notB.setOperand(R);
                CtBinaryOperator<?> or = f.Code().createBinaryOperator(notA, notB, BinaryOperatorKind.OR);
                CtUnaryOperator<?> notAll = f.Core().createUnaryOperator(); notAll.setKind(UnaryOperatorKind.NOT); notAll.setOperand(or);
                return notAll;
            }
            // ||
            case OR -> {
                CtUnaryOperator<?> notA = f.Core().createUnaryOperator(); notA.setKind(UnaryOperatorKind.NOT); notA.setOperand(L);
                CtUnaryOperator<?> notB = f.Core().createUnaryOperator(); notB.setKind(UnaryOperatorKind.NOT); notB.setOperand(R);
                CtBinaryOperator<?> and = f.Code().createBinaryOperator(notA, notB, BinaryOperatorKind.AND);
                CtUnaryOperator<?> notAll = f.Core().createUnaryOperator(); notAll.setKind(UnaryOperatorKind.NOT); notAll.setOperand(and);
                return notAll;
            }
            // ==
            case EQ -> {
                CtBinaryOperator<?> ne = f.Code().createBinaryOperator(L, R, BinaryOperatorKind.NE);
                CtUnaryOperator<?> not = f.Core().createUnaryOperator(); not.setKind(UnaryOperatorKind.NOT); not.setOperand(ne);
                return not;
            }
            // !=
            case NE -> {
                CtBinaryOperator<?> eq = f.Code().createBinaryOperator(L, R, BinaryOperatorKind.EQ);
                CtUnaryOperator<?> not = f.Core().createUnaryOperator(); not.setKind(UnaryOperatorKind.NOT); not.setOperand(eq);
                return not;
            }
            // <
            case LT -> {
                CtBinaryOperator<?> ge = f.Code().createBinaryOperator(L, R, BinaryOperatorKind.GE);
                CtUnaryOperator<?> not = f.Core().createUnaryOperator(); not.setKind(UnaryOperatorKind.NOT); not.setOperand(ge);
                return not;
            }
            // >
            case GT -> {
                CtBinaryOperator<?> le = f.Code().createBinaryOperator(L, R, BinaryOperatorKind.LE);
                CtUnaryOperator<?> not = f.Core().createUnaryOperator(); not.setKind(UnaryOperatorKind.NOT); not.setOperand(le);
                return not;
            }
            // <=
            case LE -> {
                CtBinaryOperator<?> gt = f.Code().createBinaryOperator(L, R, BinaryOperatorKind.GT);
                CtUnaryOperator<?> not = f.Core().createUnaryOperator(); not.setKind(UnaryOperatorKind.NOT); not.setOperand(gt);
                return not;
            }
            // >=
            case GE -> {
                CtBinaryOperator<?> lt = f.Code().createBinaryOperator(L, R, BinaryOperatorKind.LT);
                CtUnaryOperator<?> not = f.Core().createUnaryOperator(); not.setKind(UnaryOperatorKind.NOT); not.setOperand(lt);
                return not;
            }
            // <<
            case SL -> {
                Integer kLit = asIntLiteral(R);
                if (kLit != null && kLit >= 2) {
                    CtBinaryOperator<?> first = f.Code().createBinaryOperator(L, f.Code().createLiteral(kLit - 1), BinaryOperatorKind.SL);
                    return f.Code().createBinaryOperator(first, f.Code().createLiteral(1), BinaryOperatorKind.SL);
                }
                return null;
            }
            // >>
            case SR -> {
                Integer kLit = asIntLiteral(R);
                if (kLit != null && kLit >= 2) {
                    CtBinaryOperator<?> first = f.Code().createBinaryOperator(L, f.Code().createLiteral(1), BinaryOperatorKind.SR);
                    return f.Code().createBinaryOperator(first, f.Code().createLiteral(kLit - 1), BinaryOperatorKind.SR);
                }
                return null;
            }
            // >>>
            case USR -> {
                Integer kLit = asIntLiteral(R);
                if (kLit != null && kLit >= 2) {
                    CtBinaryOperator<?> first = f.Code().createBinaryOperator(L, f.Code().createLiteral(1), BinaryOperatorKind.USR);
                    return f.Code().createBinaryOperator(first, f.Code().createLiteral(kLit - 1), BinaryOperatorKind.USR);
                }
                return null;
            }
            // instanceof
            case INSTANCEOF -> {
                CtBinaryOperator<?> orig = f.Code().createBinaryOperator(L, R, BinaryOperatorKind.INSTANCEOF);
                CtUnaryOperator<?> not1 = f.Core().createUnaryOperator(); not1.setKind(UnaryOperatorKind.NOT); not1.setOperand(orig);
                CtUnaryOperator<?> not2 = f.Core().createUnaryOperator(); not2.setKind(UnaryOperatorKind.NOT); not2.setOperand(not1);
                return not2;
            }
            default -> {return null;}
        }
    }
    // helper for getting the integer values for the shift operators
    private Integer asIntLiteral(CtExpression<?> e) {
        if (e instanceof CtLiteral<?> lit && lit.getValue() instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
