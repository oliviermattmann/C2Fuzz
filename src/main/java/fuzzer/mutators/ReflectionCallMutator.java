package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtCatchVariable;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtTry;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class ReflectionCallMutator implements Mutator {
    private static final Logger LOGGER = LoggingConfig.getLogger(ReflectionCallMutator.class);
    Random random;

    public ReflectionCallMutator(Random random) {
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
        List<CtInvocation<?>> candidates = new ArrayList<>();

        // we don't want to get the main function invocation
        candidates.addAll(
            clazz.getElements(e -> e instanceof CtInvocation<?> inv
                && !"<init>".equals(inv.getExecutable().getSimpleName()))
        );

        if (candidates.isEmpty()) {
            LOGGER.fine("No invocation candidates found in class: " + clazz.getSimpleName());
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No invocation candidates found");
        }

        CtInvocation<?> chosen = candidates.get(random.nextInt(candidates.size()));
        LOGGER.fine("Chosen invocation: " + chosen);
        String methodName = chosen.getExecutable().getSimpleName();

        String className;
        String targetStr;

        // determine className + target
        if (chosen.getTarget() != null && chosen.getTarget().getType() != null) {
            // explicit target available (instance call like s1.length())
            className = chosen.getTarget().getType().getQualifiedName();
            targetStr = chosen.getTarget().toString();
            if (targetStr.isEmpty()) {
                // edge case: target exists but is empty (like inner class calls)
                targetStr = "null";
            }
            boolean isStatic = chosen.getExecutable().isStatic();
            targetStr = isStatic ? "null" : targetStr;
            LOGGER.fine("Detected static: " + isStatic);                
        } else {
            // fallback: use declaring type (method belongs to some class)
            if (chosen.getExecutable().getDeclaringType() != null) {
                className = chosen.getExecutable().getDeclaringType().getQualifiedName();
            } else {
                className = clazz.getQualifiedName();
            }

            // detect static call
            boolean isStatic = chosen.getExecutable().isStatic();
            targetStr = isStatic ? "null" : "this";
            LOGGER.fine("Detected static: " + isStatic);
        }
        LOGGER.fine("Detected targetStr: " + targetStr);
        // guard against "void" sneaking in as className
        if ("void".equals(className)) {
            if (chosen.getExecutable().getDeclaringType() != null) {
                className = chosen.getExecutable().getDeclaringType().getQualifiedName();
            } else {
                className = clazz.getQualifiedName();
            }
        }

        // build invoke args
        String invokeArgsSuffix = "";
        if (!chosen.getArguments().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chosen.getArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(chosen.getArguments().get(i).toString());
            }
            invokeArgsSuffix = ", " + sb;
        }


        // Get the parent statement of the chosen invocation
        CtElement current = chosen;
        CtStatement parentStatement = null;
        CtBlock<?> parentBlock = null;
        while (current != null) {
            LOGGER.fine(String.format("Considering parent: %s", current.getClass().getSimpleName()));
            if (current instanceof CtBlock) {
                // Reached a block without finding a statement
                // Keep chosen as parentStatement 
                parentStatement = chosen;
                parentBlock = parentStatement.getParent(CtBlock.class);
                break;
            }else if (current instanceof CtStatement && !(current instanceof CtBlock) && !(current instanceof CtInvocation)) {
                parentStatement = (CtStatement) current;
                parentBlock = parentStatement.getParent(CtBlock.class);
                break;
            }
            current = current.getParent();
        }

        if (parentStatement == null) {
            LOGGER.fine("Could not find a parent statement for invocation: " + chosen);
            LOGGER.fine("Skipping mutation for this invocation.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No parent statement found for chosen invocation");
        }

        if (parentBlock == null) {
            LOGGER.fine("No parent block found for: " + parentStatement);
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No parent block found for chosen invocation");
        }

        // Create the CtTry
        CtTry tryBlock = factory.Core().createTry();
        CtBlock<?> tryBody = factory.Core().createBlock();

        // build reflection replacement
        String replacement =
            "Class.forName(\"" + className + "\")" +
            ".getDeclaredMethod(\"" + methodName + "\")" +
            ".invoke(" + targetStr + invokeArgsSuffix + ")";

        // add cast if needed
        CtTypeReference<?> chosenType = chosen.getType();
        if (chosenType != null && !"void".equals(chosenType.getQualifiedName())) {
            replacement = "((" + chosenType.getQualifiedName() + ") " + replacement + ")";
        } else if (chosenType == null) {
            LOGGER.fine("Chosen invocation has no type, skipping cast.");
        }

        CtElement parent = chosen.getParent();

        // Check if the chosen invocation is the expression of a local variable
        if (parent instanceof CtLocalVariable) {
            chosen.replace(factory.Code().createCodeSnippetExpression(replacement));
        }

        else if (parent instanceof CtExpression) {
            chosen.replace(factory.Code().createCodeSnippetExpression(replacement));
        }

        // Check if the chosen invocation **is the statement itself**
        else if (chosen instanceof CtStatement) {
            // e.g., System.out.println(...)
            chosen.replace(factory.Code().createCodeSnippetStatement(replacement));
        }

        // Check if parent is a statement, but chosen is just part of it (e.g., RHS of assignment)
        else if (parent instanceof CtStatement) {
            // Keep the statement intact, replace the expression only
            chosen.replace(factory.Code().createCodeSnippetExpression(replacement));
        }
        

        

        // Anything else (expression in method argument, return, etc.)
        else {
            chosen.replace(factory.Code().createCodeSnippetExpression(replacement));
        }

        // wrap the parent block in try catch
        tryBody = parentBlock.clone();
        tryBlock.setBody(tryBody);


        // Create catch variable: (Exception e)
        CtCatchVariable<Exception> catchVar = factory.Code()
            .createCatchVariable(factory.Type().createReference(Exception.class), "e");

        // Create catch block body
        CtBlock<?> catchBody = factory.Core().createBlock();
        catchBody.addStatement(factory.Code().createCodeSnippetStatement("throw new RuntimeException(e)"));

        // Create catch and attach
        CtCatch catchBlock = factory.Core().createCatch();
        catchBlock.setParameter(catchVar);
        catchBlock.setBody(catchBody);
        tryBlock.addCatcher(catchBlock);

        // create CtBlock to wrap try-catch
        CtBlock<?> wrapperBlock = factory.Core().createBlock();
        wrapperBlock.addStatement(tryBlock);

        parentBlock.replace(wrapperBlock);            

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
                e instanceof CtInvocation<?> inv && !"<init>".equals(inv.getExecutable().getSimpleName())
            ).isEmpty();
            if (hasCandidate) {
                return true;
            }
        }
        return false;
    }
}
