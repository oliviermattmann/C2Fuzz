package fuzzer.mutators;

import java.util.List;
import java.util.Random;

import fuzzer.logging.LoggingConfig;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;


public class AutoboxEliminationMutator implements Mutator {
    private static final java.util.logging.Logger LOGGER = LoggingConfig.getLogger(AutoboxEliminationMutator.class);
    private final Random random;
    public AutoboxEliminationMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        LOGGER.fine("Autobox elimination mutation in progress.");
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

        LOGGER.fine(String.format("Mutating class: %s", clazz.getSimpleName()));

        List<CtExpression<?>> candidates = new java.util.ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for autobox candidates");
            collectBoxableCandidatesFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting autobox candidates from hot method " + hotMethod.getSimpleName());
                collectBoxableCandidates(hotMethod, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No autobox candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for autobox candidates");
                }
                collectBoxableCandidates(clazz, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No autobox candidates in class; scanning entire model");
                collectBoxableCandidatesFromModel(ctx, candidates);
            }
        }
    
        LOGGER.fine("Found " + candidates.size() + " candidate(s) in class " + clazz.getSimpleName());

        if (candidates.isEmpty()) {
            LOGGER.fine("No candidates found for autobox elimination in class " + clazz.getSimpleName());
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates found for autobox elimination");
        }

        CtExpression<?> chosen = candidates.get(random.nextInt(candidates.size()));
        String primitiveName = chosen.getType().getSimpleName();
        String wrapperClass = getWrapperFor(primitiveName);
        LOGGER.fine(String.format("type simple name: %s", primitiveName));
        if (wrapperClass == null) {
            LOGGER.fine("No wrapper class found for type: " + primitiveName);
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No wrapper class found for type: " + primitiveName);
        }

        CtTypeReference<?> wrapperType = factory.Type().createReference(wrapperClass);
        CtTypeReference<?> primitiveType = chosen.getType();

        @SuppressWarnings("unchecked")
        CtExecutableReference<Object> valueOfRef = (CtExecutableReference<Object>) (CtExecutableReference<?>) factory.Executable().createReference(
            wrapperType,
            wrapperType,
            "valueOf",
            primitiveType
        );

        CtTypeAccess<?> targetAccess = factory.Code().createTypeAccess(wrapperType);
        CtInvocation<Object> boxedCall = factory.Core().createInvocation();
        boxedCall.setType(wrapperType.clone());
        boxedCall.setTarget(targetAccess);
        boxedCall.setExecutable(valueOfRef);
        boxedCall.setArguments(java.util.List.of(chosen.clone()));

        LOGGER.fine("Mutated " + chosen + " -> " + boxedCall);
        chosen.replace(boxedCall);

    
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && hasBoxableCandidate(method)) {
                return true;
            }
            if (hasBoxableCandidate(clazz)) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (hasBoxableCandidate(c)) {
                return true;
            }
        }
        return false;
    }
    
    private void collectBoxableCandidates(CtElement root, List<CtExpression<?>> candidates) {
        if (root == null) {
            return;
        }
        candidates.addAll(
            root.getElements(e -> e instanceof CtExpression<?> expr && isBoxableCandidate(expr))
        );
    }

    private void collectBoxableCandidatesFromModel(MutationContext ctx, List<CtExpression<?>> candidates) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            collectBoxableCandidates((CtClass<?>) element, candidates);
        }
    }

    private boolean hasBoxableCandidate(CtElement root) {
        return root != null && !root.getElements(e -> e instanceof CtExpression<?> expr && isBoxableCandidate(expr)).isEmpty();
    }

    private boolean isBoxableCandidate(CtExpression<?> expr) {
        return isBoxableExpression(expr)
            && expr.getType() != null
            && getWrapperFor(expr.getType().getSimpleName()) != null;
    }

    private String getWrapperFor(String primitiveName) {
        return switch (primitiveName) {
            case "int"    -> "java.lang.Integer";
            case "boolean"-> "java.lang.Boolean";
            case "char"   -> "java.lang.Character";
            case "byte"   -> "java.lang.Byte";
            case "short"  -> "java.lang.Short";
            case "long"   -> "java.lang.Long";
            case "float"  -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            default       -> null;
        };
    }

    private boolean isBoxableExpression(CtExpression<?> expr) {
        if (expr.getType() == null) {
            return false;
        }

        if ("void".equals(expr.getType().getSimpleName())) {
            return false;
        }

        if (!expr.getType().isPrimitive()) {
            return false;
        }

        if (isInConstantContext(expr)) {
            return false;
        }

        CtElement parent = expr.getParent();

        if (parent instanceof CtAssignment<?, ?> assignment) {
            return assignment.getAssignment() == expr;
        }

        if (parent instanceof CtInvocation<?>) {
            return true;
        }

        if (parent instanceof CtBinaryOperator<?>) {
            return true;
        }

        if (parent instanceof CtReturn<?>) {
            return true;
        }

        if (parent instanceof CtUnaryOperator<?> unary) {
            switch (unary.getKind()) {
                case POSTINC:
                case POSTDEC:
                case PREINC:
                case PREDEC:
                    return false;
                default:
                    // others should be fine
                    return true;
            }
        }

        return false;
    }

    private boolean isInConstantContext(CtExpression<?> expr) {
        CtElement current = expr;
        while (current != null) {
            if (current instanceof CtField<?> field) {
                if (field.hasModifier(ModifierKind.FINAL) && field.hasModifier(ModifierKind.STATIC)) {
                    return true;
                }
            }
            if (current instanceof CtAnnotation<?>) {
                return true;
            }
            if (current instanceof CtCase<?>) {
                return true;
            }
            if (current instanceof CtEnumValue<?>) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
    
}
