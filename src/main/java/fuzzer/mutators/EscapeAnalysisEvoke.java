package fuzzer.mutators;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import fuzzer.logging.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCodeSnippetExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;


public class EscapeAnalysisEvoke implements Mutator {
    private static final java.util.logging.Logger LOGGER = LoggingConfig.getLogger(AutoboxEliminationEvoke.class);
    private final Random random;
    private final Set<String> registeredWrappers = new java.util.HashSet<>();
    private static final Set<String> WRAPPER_SUFFIXES = Set.of(
            "Integer",
            "Boolean",
            "Character",
            "Byte",
            "Short",
            "Long",
            "Float",
            "Double"
    );

    public EscapeAnalysisEvoke(Random random) {
        this.random = random;
    }
    @Override
    public MutationResult mutate(MutationContext ctx) {
        CtModel model = ctx.model();
        Factory factory = ctx.factory();
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> hotMethod = ctx.targetMethod();
        if (clazz == null || isWrapperClass(clazz)) {
            clazz = pickRandomNonWrapperClass(model);
            hotMethod = null;
        }
        if (clazz == null) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
        }
        if (hotMethod != null && hotMethod.getDeclaringType() != clazz) {
            hotMethod = null;
        }

        LOGGER.fine(String.format("Mutating class: %s", clazz.getSimpleName()));
        // AstTreePrinter printer = new AstTreePrinter();
        // printer.scan(clazz);

        registeredWrappers.clear();
        registerExistingWrappers(clazz);
    
        // Filter out expressions that are suitable for creating helper classes with
        List<CtExpression<?>> candidates = new java.util.ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for escape-analysis candidates");
            collectEscapeCandidatesFromModel(ctx, candidates);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting escape-analysis candidates from hot method " + hotMethod.getSimpleName());
                collectEscapeCandidates(hotMethod, candidates);
            }
            if (candidates.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No escape-analysis candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for escape-analysis candidates");
                }
                collectEscapeCandidates(clazz, candidates);
            }
            if (candidates.isEmpty()) {
                LOGGER.fine("No escape-analysis candidates in class; scanning entire model");
                collectEscapeCandidatesFromModel(ctx, candidates);
            }
        }
    
        LOGGER.fine("Found " + candidates.size() + " candidate(s) in class " + clazz.getSimpleName());

        if (candidates.isEmpty()) {
            LOGGER.fine("No candidates found for escape analysis in class " + clazz.getSimpleName());
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No candidates found for escape analysis");
        }

        CtExpression<?> chosen = candidates.get(random.nextInt(candidates.size()));
        LOGGER.fine(String.format("Chosen expression: %s", chosen.toString()));
        LOGGER.fine(String.format("type simple name: %s", chosen.getType().getSimpleName()));

        // check if we are in a static context
        // if so our wrapper class must be static too
        CtMethod<?> parentMethod = chosen.getParent(CtMethod.class);
        boolean inStaticContext = parentMethod != null && parentMethod.isStatic();


        
        // get the wrappe
        String wrapperTypeString = getWrapperNameFor(chosen.getType().getSimpleName());
        if (wrapperTypeString == null) {
            LOGGER.warning("Unsupported primitive type: " + chosen.getType().getSimpleName());
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Unsupported primitive type: " + chosen.getType().getSimpleName());
        }

        createWrapperFor(factory, clazz, chosen.getType().getSimpleName(), inStaticContext);

        String wrapperName = "My" + getWrapperNameFor(chosen.getType().getSimpleName());

        // Build snippet: new MyX(chosen).v()
        String snippet = String.format("new %s(%s).v()", wrapperName, chosen.toString());

        // Create a CtCodeSnippetExpression and replace chosen
        CtCodeSnippetExpression<?> replacement = factory.Code().createCodeSnippetExpression(snippet);
        chosen.replace(replacement);

        

        

    
        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        registeredWrappers.clear();
        if (isWrapperClass(clazz)) {
            clazz = null;
            method = null;
        }
        if (clazz != null) {
            registerExistingWrappers(clazz);
            if (method != null && method.getDeclaringType() == clazz && hasEscapeCandidate(method)) {
                return true;
            }
            if (hasEscapeCandidate(clazz)) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (isWrapperClass(c)) {
                continue;
            }
            registerExistingWrappers(c);
            if (hasEscapeCandidate(c)) {
                return true;
            }
        }
        return false;
    }
    
    private CtClass<?> pickRandomNonWrapperClass(CtModel model) {
        if (model == null) {
            return null;
        }
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return null;
        }
        List<CtClass<?>> filtered = new java.util.ArrayList<>();
        for (CtElement element : classes) {
            CtClass<?> candidate = (CtClass<?>) element;
            if (!isWrapperClass(candidate)) {
                filtered.add(candidate);
            }
        }
        if (filtered.isEmpty()) {
            return null;
        }
        return filtered.get(random.nextInt(filtered.size()));
    }

    private boolean isWrapperClass(CtClass<?> clazz) {
        return clazz != null && isGeneratedWrapperName(clazz.getSimpleName());
    }
    
    private String getWrapperNameFor(String primitiveName) {
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

    private void collectEscapeCandidates(CtElement root, List<CtExpression<?>> candidates) {
        if (root == null) {
            return;
        }
        if (root instanceof CtClass<?> classRoot) {
            if (isWrapperClass(classRoot)) {
                return;
            }
            registerExistingWrappers(classRoot);
        } else if (root instanceof CtMethod<?> method) {
            CtType<?> declaring = method.getDeclaringType();
            if (declaring instanceof CtClass<?> declaringClass) {
                registerExistingWrappers(declaringClass);
            }
        }
        candidates.addAll(
            root.getElements(e -> e instanceof CtExpression<?> expr && isEscapeCandidate(expr))
        );
    }

    private void collectEscapeCandidatesFromModel(MutationContext ctx, List<CtExpression<?>> candidates) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (isWrapperClass(c)) {
                continue;
            }
            collectEscapeCandidates(c, candidates);
        }
    }

    private boolean hasEscapeCandidate(CtElement root) {
        return root != null && !root.getElements(e -> e instanceof CtExpression<?> expr && isEscapeCandidate(expr)).isEmpty();
    }

    private boolean isEscapeCandidate(CtExpression<?> expr) {
        return isBoxableExpression(expr)
            && expr.getType() != null
            && getWrapperNameFor(expr.getType().getSimpleName()) != null
            && !isInsideGeneratedWrapper(expr);
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

        CtElement parent = expr.getParent();

        // Expression is the RHS of an assignment
        if (parent instanceof CtAssignment<?, ?> assignment) {
            return assignment.getAssignment() == expr;
        }

        // Expression is used as a method argument
        if (parent instanceof CtInvocation<?>) {
            return true;
        }

        // Expression is part of a binary operator (e.g., a + b)
        if (parent instanceof CtBinaryOperator<?>) {
            return true;
        }

        // Expression is used in a return statement
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
                    // other unary operators should be fine
                    return true;
            }
        }

        // Default: not safe to autobox
        return false;
    }

    private void createWrapperFor(Factory factory, CtClass<?> parentClass, String primitiveName, boolean makeStatic) {
        String suffix = getWrapperNameFor(primitiveName);    
        String wrapperName = "My" + suffix;
        if (!registeredWrappers.add(qualifiedWrapperName(parentClass, wrapperName))) {
            return;
        }
    
        // Avoid duplicates
        Optional<CtType<?>> existing = parentClass.getNestedTypes()
            .stream()
            .filter(t -> t.getSimpleName().equals(wrapperName))
            .findFirst();
        if (existing.isPresent()) {
            return;
        }

        CtClass<?> wrapperClass = factory.Core().createClass();
        wrapperClass.setSimpleName(wrapperName);
        if (makeStatic) {
            wrapperClass.setModifiers(EnumSet.of(ModifierKind.STATIC));
        }
    
    
        // create the primitive field for class
        CtTypeReference<?> primType = factory.Type().createReference(primitiveName);
        CtField<?> field = factory.Field().create(
                wrapperClass,
                EnumSet.of(ModifierKind.PUBLIC),
                primType,
                "v"
        );
        wrapperClass.addField(field);
    
        // create constructor for class
        CtConstructor<?> constructor = factory.Core().createConstructor();
        constructor.addModifier(ModifierKind.PUBLIC);
        CtParameter<?> param = factory.Core().createParameter();
        param.setType(primType);
        param.setSimpleName("v");
        constructor.addParameter(param);
        CtBlock<?> body = factory.Core().createBlock();
        body.addStatement(factory.Code().createCodeSnippetStatement("this.v = v"));
        constructor.setBody(body);
        wrapperClass.addConstructor((CtConstructor) constructor);
    
        // finally create getter method for values
        CtMethod<?> vMethod = factory.Method().create(
                wrapperClass,
                EnumSet.of(ModifierKind.PUBLIC),
                primType,
                "v",
                List.of(),
                Set.of()
        );
        CtBlock<?> methodBody = factory.Core().createBlock();
        methodBody.addStatement(factory.Code().createCodeSnippetStatement("return v"));
        vMethod.setBody(methodBody);
        wrapperClass.addMethod(vMethod);
    
        parentClass.addNestedType(wrapperClass);
    }
   
    private void registerExistingWrappers(CtClass<?> clazz) {
        for (CtType<?> nested : clazz.getNestedTypes()) {
            if (nested instanceof CtClass<?> nestedClass) {
                if (isGeneratedWrapperName(nestedClass.getSimpleName())) {
                    registeredWrappers.add(qualifiedWrapperName(clazz, nestedClass.getSimpleName()));
                }
                registerExistingWrappers(nestedClass);
            }
        }
    }

    private boolean isInsideGeneratedWrapper(CtElement element) {
        if (element == null) {
            return false;
        }
        CtClass<?> current = element.getParent(CtClass.class);
        while (current != null) {
            String qualifiedName = safeQualifiedName(current);
            if (qualifiedName != null && registeredWrappers.contains(qualifiedName)) {
                return true;
            }
            current = current.getParent(CtClass.class);
        }
        return false;
    }

    private boolean isGeneratedWrapperName(String name) {
        if (name == null || name.length() <= 2 || !name.startsWith("My")) {
            return false;
        }
        return WRAPPER_SUFFIXES.contains(name.substring(2));
    }

    private String qualifiedWrapperName(CtClass<?> parentClass, String wrapperName) {
        String owner = safeQualifiedName(parentClass);
        if (owner == null || owner.isEmpty()) {
            return wrapperName;
        }
        return owner + "." + wrapperName;
    }

    private String safeQualifiedName(CtClass<?> clazz) {
        try {
            return clazz.getQualifiedName();
        } catch (Exception ex) {
            return clazz.getSimpleName();
        }
    }
}
