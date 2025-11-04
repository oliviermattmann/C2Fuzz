package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;


public class InlineEvokeMutator implements Mutator {
    Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(InlineEvokeMutator.class);

    public InlineEvokeMutator(Random random) {
        this.random = random;
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz) {
                boolean methodHasCandidate = !method.getElements(e ->
                    e instanceof CtBinaryOperator<?> bin && bin.getParent(CtMethod.class) != null
                ).isEmpty();
                if (methodHasCandidate) {
                    return true;
                }
            }
            boolean classHasCandidate = !clazz.getElements(e ->
                e instanceof CtBinaryOperator<?> bin && bin.getParent(CtMethod.class) != null
            ).isEmpty();
            if (classHasCandidate) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            boolean hasCandidate = !c.getElements(e ->
                e instanceof CtBinaryOperator<?> bin && bin.getParent(CtMethod.class) != null
            ).isEmpty();
            if (hasCandidate) {
                return true;
            }
        }
        return false;
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

            // Find all binary expressions that are not a child of another binary expression
            // we could also just pick a random binary expression, but this way we avoid changing the computation (not really necessary though)
            List<CtBinaryOperator<?>> binOps = new ArrayList<>();
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting inline candidates from hot method " + hotMethod.getSimpleName());
                hotMethod.getElements(e -> e instanceof CtBinaryOperator<?>).forEach(binOp -> {
                    binOps.add((CtBinaryOperator<?>) binOp);
                });
            }
            if (binOps.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No inline candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for inline candidates");
                }
                clazz.getElements(e -> e instanceof CtBinaryOperator<?>).forEach(binOp -> {
                    binOps.add((CtBinaryOperator<?>) binOp);
                });
            }

            // If there are no top-level binary expressions, skip this class
            if (binOps.isEmpty()) {
                return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No binary expressions found");
            }

            // Select a random top-level binary expression
            CtBinaryOperator<?> binOp = binOps.get(random.nextInt(binOps.size()));

            CtMethod<?> enclosingMethod = binOp.getParent(CtMethod.class);
            if (enclosingMethod == null) {
                // must live inside a concrete method
                return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Binary expression not inside a method");
            }

            CtType<?> owningType = enclosingMethod.getDeclaringType();
            if (owningType == null) {
                owningType = clazz; // fallback to original selection
            }

            boolean isStaticContext = enclosingMethod.hasModifier(ModifierKind.STATIC);

            CtMethod<?> helper = factory.createMethod();
            helper.addModifier(ModifierKind.PRIVATE);
            if (isStaticContext) {
                helper.addModifier(ModifierKind.STATIC);
            }
            helper.setSimpleName("foo" + System.nanoTime());
            CtTypeReference<?> returnType = binOp.getType() != null
                    ? binOp.getType()
                    : factory.Type().createReference(Object.class);
            helper.setType(returnType);

            // Recursively clone the expression, replacing literals with parameters
            CtExpression<?> newExpr = buildParameterizedExpression(binOp, helper, factory);

            // Generate return statement and method body
            CtReturn<?> returnStmt = factory.createReturn();
            returnStmt.setReturnedExpression((CtExpression) newExpr);
            CtBlock<?> body = factory.createBlock();
            body.addStatement(returnStmt);
            helper.setBody(body);
            owningType.addMethod(helper);

            // Replace original expression with a call to the helper method
            CtInvocation<?> call = factory.createInvocation();
            call.setExecutable((CtExecutableReference) helper.getReference());
            addArgumentsToInvocation(binOp, call, factory);
            binOp.replace(call);


            MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
            return result;
    }

    // Recursively build a parameterized version of the expression
    private CtExpression<?> buildParameterizedExpression(CtExpression<?> expression, CtMethod<?> helper, Factory factory) {

        if (expression instanceof CtBinaryOperator) {
            CtBinaryOperator<?> original = (CtBinaryOperator<?>) expression;
            CtBinaryOperator<?> newOp = factory.createBinaryOperator();
            newOp.setKind(original.getKind());
            newOp.setLeftHandOperand(buildParameterizedExpression(original.getLeftHandOperand(), helper, factory));
            newOp.setRightHandOperand(buildParameterizedExpression(original.getRightHandOperand(), helper, factory));
            return newOp;
        } 
        else if (expression instanceof CtUnaryOperator<?> original) {
            UnaryOperatorKind kind = original.getKind();
            boolean hasSideEffect = switch (kind) {
                case POSTINC, POSTDEC, PREINC, PREDEC -> true;
                default -> false;
            };
        
            if (hasSideEffect) {
                return parameterizeLeaf(original, helper, factory);
            } else {
                // recurse as before
                CtUnaryOperator<?> newOp = factory.createUnaryOperator();
                newOp.setKind(kind);
                newOp.setOperand(buildParameterizedExpression(original.getOperand(), helper, factory));
                return newOp;
            }
        }


        else if (expression instanceof CtLiteral
                || expression instanceof CtVariableRead
                || expression instanceof CtVariableWrite
                || expression instanceof CtArrayAccess
                || expression instanceof CtConditional) {
            return parameterizeLeaf(expression, helper, factory);
        } 
        else if (expression instanceof CtInvocation<?> original) {
            // Treat the entire invocation as a parameterized leaf
            return parameterizeLeaf(original, helper, factory);
        }
        else {
            // For other expression types, just clone them
            return expression.clone();
        }
    }

    private void addArgumentsToInvocation(CtExpression<?> expression, CtInvocation<?> invocation, Factory factory) {
        if (expression instanceof CtBinaryOperator) {
            CtBinaryOperator<?> binOp = (CtBinaryOperator<?>) expression;
            addArgumentsToInvocation(binOp.getLeftHandOperand(), invocation, factory);
            addArgumentsToInvocation(binOp.getRightHandOperand(), invocation, factory);
        }
        else if (expression instanceof CtUnaryOperator<?> unOp) {
            UnaryOperatorKind kind = unOp.getKind();
            boolean hasSideEffect = switch (kind) {
                case POSTINC, POSTDEC, PREINC, PREDEC -> true;
                default -> false;
            };
        
            if (hasSideEffect) {
                // matches the parameterized leaf
                invocation.addArgument(unOp.clone());
            } else {
                // recurse like in buildParameterizedExpression
                addArgumentsToInvocation(unOp.getOperand(), invocation, factory);
            }
        }
        else if (expression instanceof CtInvocation<?> original) {
            // add the whole invocation as an argument
            invocation.addArgument(original.clone());
        }
        else if (expression instanceof CtArrayAccess<?, ?> arrayAccess) {
            invocation.addArgument(arrayAccess.clone());
        }
        else if (expression instanceof CtConditional<?> conditional) {
            invocation.addArgument(conditional.clone());
        }
        else {
            // It's a literal or a variable, add it as an argument
            invocation.addArgument(expression.clone());
        }
    }

    private CtExpression<?> parameterizeLeaf(CtExpression<?> expression, CtMethod<?> helper, Factory factory) {
        CtParameter<?> param = factory.createParameter();
        CtTypeReference<?> type = expression.getType() != null
                ? expression.getType()
                : factory.Type().createReference(Object.class);
        param.setType(type);
        param.setSimpleName("p" + helper.getParameters().size());
        helper.addParameter(param);
        return factory.createVariableRead(param.getReference(), false);
    }
}
