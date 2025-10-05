package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import fuzzer.util.AstTreePrinter;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;


public class InlineEvokeMutator implements Mutator {
    Random random;

    public InlineEvokeMutator(Random random) {
        this.random = random;
    }

    @Override
    public Launcher mutate(Launcher launcher, CtModel model, Factory factory) {

        
            CtClass<?> clazz = (CtClass<?>) model.getElements(e -> e instanceof CtClass<?> ct && ct.isPublic()).get(0);
            // Find all binary expressions that are not a child of another binary expression
            // we could also just pick a random binary expression, but this way we avoid changing the computation (not really necessary though)
            List<CtBinaryOperator<?>> binOps = new ArrayList<>();
            clazz.getElements(e -> e instanceof CtBinaryOperator<?>).forEach(binOp -> {
                binOps.add((CtBinaryOperator<?>) binOp);
            });

            // If there are no top-level binary expressions, skip this class
            if (binOps.isEmpty()) {
                return null;
            }

            // Select a random top-level binary expression
            CtBinaryOperator<?> binOp = binOps.get(random.nextInt(binOps.size()));

            // check in which context we are (static or non-static)
            boolean isStaticContext = binOp.getParent(CtMethod.class).isStatic();

            // Generate a helper method.
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
            clazz.addMethod(helper);

            // Replace original expression with a call to the helper method
            CtInvocation<?> call = factory.createInvocation();
            call.setExecutable((CtExecutableReference) helper.getReference());
            addArgumentsToInvocation(binOp, call, factory);
            binOp.replace(call);


        return launcher;
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
                // parameterize the whole thing
                CtParameter<?> param = factory.createParameter();
                CtTypeReference<?> t = original.getType() != null
                    ? original.getType()
                    : factory.Type().createReference(Object.class);
                param.setType(t);
                param.setSimpleName("p" + helper.getParameters().size());
                helper.addParameter(param);
                return factory.createVariableRead(param.getReference(), false);
            } else {
                // recurse as before
                CtUnaryOperator<?> newOp = factory.createUnaryOperator();
                newOp.setKind(kind);
                newOp.setOperand(buildParameterizedExpression(original.getOperand(), helper, factory));
                return newOp;
            }
        }


        else if (expression instanceof CtLiteral || expression instanceof CtVariableRead || expression instanceof CtVariableWrite) {
            CtParameter<?> param = factory.createParameter();
            CtTypeReference<?> t = expression.getType() != null
                    ? expression.getType()
                    : factory.Type().createReference(Object.class);
            param.setType(t);
            param.setSimpleName("p" + helper.getParameters().size());
            helper.addParameter(param);
            return factory.createVariableRead(param.getReference(), false);
        } 
        else if (expression instanceof CtInvocation<?> original) {
            // Treat the entire invocation as a parameterized leaf
            CtParameter<?> param = factory.createParameter();
            CtTypeReference<?> t = original.getType() != null
                ? original.getType()
                : factory.Type().createReference(Object.class);
            param.setType(t);
            param.setSimpleName("p" + helper.getParameters().size());
            helper.addParameter(param);
            return factory.createVariableRead(param.getReference(), false);
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
        else {
            // It's a literal or a variable, add it as an argument
            invocation.addArgument(expression.clone());
        }
    }
}

