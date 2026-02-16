package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Converts int-indexed for-loops to long-indexed loops, avoiding cases where the
 * index is used in contexts that require int (e.g., array indices or int-only parameters).
 */
public class IntToLongLoopMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(IntToLongLoopMutator.class);

    public IntToLongLoopMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        Factory factory = ctx.factory();
        List<CtFor> candidates = findCandidates(ctx);
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No int-indexed for-loops to widen");
        }

        CtFor target = candidates.get(random.nextInt(candidates.size()));
        CtLocalVariable<?> loopVar = (CtLocalVariable<?>) target.getForInit().get(0);
        CtTypeReference<Long> longType = factory.Type().LONG_PRIMITIVE;

        loopVar.setType(longType);
        if (loopVar.getReference() != null) {
            loopVar.getReference().setType(longType);
        }
        maybeWidenLiteral(loopVar.getDefaultExpression(), longType);
        retargetAccessTypes(target, loopVar, longType);

        LOGGER.fine(() -> "Converted loop variable " + loopVar.getSimpleName() + " to long in loop at " + target.getPosition());
        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "Loop counter widened to long");
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        return hasCandidates(ctx);
    }

    private boolean hasCandidates(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && hasCandidatesInElement(method, ctx)) {
                return true;
            }
            if (hasCandidatesInElement(clazz, ctx)) {
                return true;
            }
        }
        return hasCandidatesInModel(ctx);
    }

    private boolean hasCandidatesInElement(CtElement root, MutationContext ctx) {
        if (root == null) {
            return false;
        }
        for (CtFor loop : root.getElements(new TypeFilter<>(CtFor.class))) {
            if (isIntIndexed(loop, ctx.factory()) && usesAreSafe(loop)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCandidatesInModel(MutationContext ctx) {
        for (CtFor loop : ctx.model().getElements(new TypeFilter<>(CtFor.class))) {
            if (isIntIndexed(loop, ctx.factory()) && usesAreSafe(loop)) {
                return true;
            }
        }
        return false;
    }

    private List<CtFor> findCandidates(MutationContext ctx) {
        List<CtFor> candidates = new ArrayList<>();
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
            collectCandidatesFromModel(ctx, candidates);
            return candidates;
        }
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            collectCandidatesFromElement(hotMethod, ctx, candidates);
        }
        if (candidates.isEmpty() && clazz != null) {
            collectCandidatesFromElement(clazz, ctx, candidates);
        }
        if (candidates.isEmpty()) {
            collectCandidatesFromModel(ctx, candidates);
        }
        return candidates;
    }

    private void collectCandidatesFromElement(CtElement root, MutationContext ctx, List<CtFor> candidates) {
        if (root == null) {
            return;
        }
        for (CtFor loop : root.getElements(new TypeFilter<>(CtFor.class))) {
            if (isIntIndexed(loop, ctx.factory()) && usesAreSafe(loop)) {
                candidates.add(loop);
            }
        }
    }

    private void collectCandidatesFromModel(MutationContext ctx, List<CtFor> candidates) {
        for (CtFor loop : ctx.model().getElements(new TypeFilter<>(CtFor.class))) {
            if (isIntIndexed(loop, ctx.factory()) && usesAreSafe(loop)) {
                candidates.add(loop);
            }
        }
    }

    private boolean isIntIndexed(CtFor loop, Factory factory) {
        if (loop.getForInit() == null || loop.getForInit().size() != 1) {
            return false;
        }
        if (!(loop.getForInit().get(0) instanceof CtLocalVariable<?> localVar)) {
            return false;
        }
        CtTypeReference<?> type = localVar.getType();
        if (type == null) {
            return false;
        }
        CtTypeReference<?> unboxed;
        if (type.isPrimitive()) {
            unboxed = type;
        } else {
            try {
                unboxed = type.unbox();
            } catch (Exception ex) {
                return false;
            }
        }
        return factory.Type().INTEGER_PRIMITIVE.equals(unboxed);
    }

    private boolean usesAreSafe(CtFor loop) {
        CtLocalVariableReference<?> idxRef = null;
        if (loop.getForInit() != null && loop.getForInit().size() == 1 && loop.getForInit().get(0) instanceof CtLocalVariable<?> initVar) {
            idxRef = initVar.getReference();
        }
        if (idxRef == null) {
            return false;
        }

        for (CtVariableAccess<?> access : loop.getElements(new TypeFilter<>(CtVariableAccess.class))) {
            if (!references(access, idxRef)) {
                continue;
            }
            CtElement parent = access.getParent();

            if (parent instanceof CtArrayAccess<?, ?> arrayAccess && arrayAccess.getIndexExpression() == access) {
                return false;
            }
            if (parent instanceof CtInvocation<?> invocation) {
                int argIndex = invocation.getArguments().indexOf(access);
                CtTypeReference<?> paramType = null;
                if (argIndex >= 0) {
                    List<CtTypeReference<?>> params = invocation.getExecutable().getParameters();
                    if (params != null && params.size() > argIndex) {
                        paramType = params.get(argIndex);
                    }
                }
                if (isIntOnly(paramType)) {
                    return false;
                }
            }
            if (parent instanceof CtConstructorCall<?> ctor) {
                int argIndex = ctor.getArguments().indexOf(access);
                CtTypeReference<?> paramType = null;
                if (argIndex >= 0) {
                    List<CtTypeReference<?>> params = ctor.getExecutable().getParameters();
                    if (params != null && params.size() > argIndex) {
                        paramType = params.get(argIndex);
                    }
                }
                if (isIntOnly(paramType)) {
                    return false;
                }
            }
            if (parent instanceof CtAssignment<?, ?> assignment && assignment.getAssignment() == access) {
                CtExpression<?> lhs = assignment.getAssigned();
                if (lhs != null && isIntOnly(lhs.getType())) {
                    return false;
                }
            }
            if (parent instanceof CtReturn<?> ctReturn && ctReturn.getReturnedExpression() == access) {
                CtMethod<?> enclosing = ctReturn.getParent(CtMethod.class);
                if (enclosing != null && isIntOnly(enclosing.getType())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isIntOnly(CtTypeReference<?> type) {
        if (type == null) {
            return false;
        }
        if (type.isPrimitive()) {
            String simple = type.getSimpleName();
            return "int".equals(simple) || "short".equals(simple) || "byte".equals(simple) || "char".equals(simple);
        }
        String qn = type.getQualifiedName();
        return "java.lang.Integer".equals(qn)
                || "java.lang.Short".equals(qn)
                || "java.lang.Byte".equals(qn)
                || "java.lang.Character".equals(qn);
    }

    private boolean references(CtVariableAccess<?> access, CtLocalVariableReference<?> ref) {
        return access.getVariable() != null && access.getVariable().equals(ref);
    }

    @SuppressWarnings("unchecked")
    private void maybeWidenLiteral(CtExpression<?> expr, CtTypeReference<Long> longType) {
        if (expr instanceof CtLiteral<?> literal) {
            Object value = literal.getValue();
            CtLiteral<Object> writable = (CtLiteral<Object>) literal;
            if (value instanceof Integer i) {
                writable.setValue((long) i);
                writable.setType(longType);
            } else if (value instanceof Short s) {
                writable.setValue((long) s);
                writable.setType(longType);
            } else if (value instanceof Byte b) {
                writable.setValue((long) b);
                writable.setType(longType);
            } else if (value instanceof Character c) {
                writable.setValue((long) c.charValue());
                writable.setType(longType);
            }
        }
    }

    private void retargetAccessTypes(CtFor loop, CtLocalVariable<?> loopVar, CtTypeReference<Long> longType) {
        CtLocalVariableReference<?> ref = loopVar.getReference();
        for (CtVariableAccess<?> access : loop.getElements(new TypeFilter<>(CtVariableAccess.class))) {
            if (!references(access, ref)) {
                continue;
            }
            access.setType(longType);
            if (access.getVariable() != null) {
                access.getVariable().setType(longType);
            }
        }
    }
}
