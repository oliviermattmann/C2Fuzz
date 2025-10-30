package fuzzer.mutators;

import java.util.List;
import java.util.Random;

import fuzzer.util.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;


public class AutoboxEliminationEvoke implements Mutator {
    private static final java.util.logging.Logger LOGGER = LoggingConfig.getLogger(AutoboxEliminationEvoke.class);
    private Random random;
    public AutoboxEliminationEvoke(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        LOGGER.fine("Autobox Elimination Evoke mutation in progress.");
        CtModel model = ctx.model();
        Factory factory = ctx.factory();
        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));

        LOGGER.fine(String.format("Mutating class: %s", clazz.getSimpleName()));

    
        List<CtExpression<?>> candidates = clazz.getElements(e -> 
            e instanceof CtExpression<?> expr && isBoxableExpression(expr)
        );
    
        LOGGER.fine("Found " + candidates.size() + " candidate(s) in class " + clazz.getSimpleName());

        if (candidates.isEmpty()) {
            LOGGER.fine("No candidates found for autobox elimination in class " + clazz.getSimpleName());
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates found for autobox elimination");
        }

        CtExpression<?> chosen = candidates.get(random.nextInt(candidates.size()));
        String wrapperClass = getWrapperFor(chosen.getType().getSimpleName());
        LOGGER.fine(String.format("type simple name: %s", chosen.getType().getSimpleName()));
        if (wrapperClass == null) {
            LOGGER.fine("No wrapper class found for type: " + chosen.getType().getSimpleName());
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No wrapper class found for type: " + chosen.getType().getSimpleName());
        }
        String replacement = wrapperClass + ".valueOf(" + chosen.toString() + ")";
        CtExpression<?> boxed = factory.Code().createCodeSnippetExpression(replacement);
        chosen.replace(boxed);
        LOGGER.fine("Mutated " + chosen + " -> " + replacement);

    
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> clazz = (CtClass<?>) element;
            boolean hasCandidate = !clazz.getElements(e ->
                e instanceof CtExpression<?> expr && isBoxableExpression(expr) && getWrapperFor(expr.getType().getSimpleName()) != null
            ).isEmpty();
            if (hasCandidate) {
                return true;
            }
        }
        return false;
    }
    
    private String getWrapperFor(String primitiveName) {
        return switch (primitiveName) {
            case "int"    -> "Integer";
            case "boolean"-> "Boolean";
            case "char"   -> "Character";
            case "byte"   -> "Byte";
            case "short"  -> "Short";
            case "long"   -> "Long";
            case "float"  -> "Float";
            case "double" -> "Double";
            default       -> null;
        };
    }

    private boolean isBoxableExpression(CtExpression<?> expr) {
        // Exclude void
        if (expr.getType() == null || expr.getType().equals(expr.getFactory().Type().voidType())) {
            return false;
        }

        // Only primitive types are candidates
        if (!expr.getType().isPrimitive()) {
            return false;
        }

        // get parent to check in what context the expression is used
        CtElement parent = expr.getParent();

        // Expression is the RHS of an assignment
        if (parent instanceof CtAssignment<?, ?> assignment) {
            return assignment.getAssignment() == expr;
        }

        // Case 2: Expression is used as a method argument
        if (parent instanceof CtInvocation<?>) {
            return true;
        }

        // Case 3: Expression is part of a binary operator (e.g., a + b)
        if (parent instanceof CtBinaryOperator<?>) {
            return true;
        }

        // Case 4: Expression is used in a return statement
        if (parent instanceof CtReturn<?>) {
            return true;
        }

        // Exclude increment/decrement (b++, ++b, etc.)
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

        // by default, do not consider it a candidate
        return false;
    }
    
}
