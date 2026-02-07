package fuzzer.mutators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtArrayRead;
import spoon.reflect.code.CtArrayWrite;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Converts simple primitive arrays into MemorySegment backed storage with Arena allocation.
 * Conservative: only method-local arrays of primitive/wrapper types, single-dimension,
 * with straightforward reads/writes and length usage.
 */
public class ArrayToMemorySegmentMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(ArrayToMemorySegmentMutator.class);

    private static final Map<String, String> LAYOUT_BY_PRIMITIVE = new HashMap<>();
    static {
        LAYOUT_BY_PRIMITIVE.put("byte", "JAVA_BYTE");
        LAYOUT_BY_PRIMITIVE.put("short", "JAVA_SHORT");
        LAYOUT_BY_PRIMITIVE.put("int", "JAVA_INT");
        LAYOUT_BY_PRIMITIVE.put("long", "JAVA_LONG");
        LAYOUT_BY_PRIMITIVE.put("float", "JAVA_FLOAT");
        LAYOUT_BY_PRIMITIVE.put("double", "JAVA_DOUBLE");
    }

    public ArrayToMemorySegmentMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        Factory factory = ctx.factory();
        List<CtLocalVariable<?>> candidates = findCandidates(ctx);
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No eligible arrays to transform");
        }

        CtLocalVariable<?> arrayVar = candidates.get(random.nextInt(candidates.size()));
        CtArrayTypeReference<?> arrayType = (CtArrayTypeReference<?>) arrayVar.getType();
        CtTypeReference<?> component = arrayType.getComponentType();
        CtTypeReference<?> primitive = component.isPrimitive() ? component : safeUnbox(component);
        String layoutConst = LAYOUT_BY_PRIMITIVE.get(primitive.getSimpleName());
        if (layoutConst == null) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Unsupported component type");
        }

        // Determine length expression
        CtExpression<?> lengthExpr = extractLengthExpression(arrayVar, factory);
        if (lengthExpr == null) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Could not determine array length");
        }

        String baseName = arrayVar.getSimpleName();
        String arenaName = baseName + "Arena";
        String lenName = baseName + "Len";

        CtTypeReference<?> arenaType = factory.Type().createReference("java.lang.foreign.Arena");
        CtTypeReference<?> segmentType = factory.Type().createReference("java.lang.foreign.MemorySegment");

        CtLocalVariable<?> arenaVar = factory.Code().createLocalVariable(
                arenaType,
                arenaName,
                factory.Code().createCodeSnippetExpression("java.lang.foreign.Arena.ofConfined()"));

        @SuppressWarnings("unchecked")
        CtExpression<Integer> lenExprClone = (CtExpression<Integer>) lengthExpr.clone();
        CtLocalVariable<?> lenVar = factory.Code().createLocalVariable(
                factory.Type().INTEGER_PRIMITIVE,
                lenName,
                lenExprClone);

        CtLocalVariable<?> segmentVar = factory.Code().createLocalVariable(
                segmentType,
                baseName,
                factory.Code().createCodeSnippetExpression(
                        arenaName + ".allocateArray(java.lang.foreign.ValueLayout." + layoutConst + ", (long) " + lenName + ")"));

        CtMethod<?> method = arrayVar.getParent(CtMethod.class);
        if (method == null) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Array not in a method");
        }

        insertDeclarations(arrayVar, arenaVar, lenVar, segmentVar);
        populateInlineInitializer(arrayVar, layoutConst, baseName, factory);

        retargetArrayAccesses(method, arrayVar, layoutConst, baseName, factory, lenName);

        LOGGER.fine(() -> "Transformed array " + baseName + " into MemorySegment with layout " + layoutConst);
        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "Array replaced with MemorySegment");
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
        Factory factory = ctx.factory();
        for (CtLocalVariable<?> local : root.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!isArray(local)) {
                continue;
            }
            CtArrayTypeReference<?> arrayType = (CtArrayTypeReference<?>) local.getType();
            if (arrayType.getDimensionCount() != 1) {
                continue;
            }
            CtTypeReference<?> comp = arrayType.getComponentType();
            CtTypeReference<?> prim = comp.isPrimitive() ? comp : safeUnbox(comp);
            if (prim == null || !LAYOUT_BY_PRIMITIVE.containsKey(prim.getSimpleName())) {
                continue;
            }
            if (!(local.getDefaultExpression() instanceof spoon.reflect.code.CtNewArray<?>)) {
                continue;
            }
            CtMethod<?> method = local.getParent(CtMethod.class);
            if (method == null) {
                continue;
            }
            if (!usagesSupported(method, local, factory)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean hasCandidatesInModel(MutationContext ctx) {
        Factory factory = ctx.factory();
        for (CtLocalVariable<?> local : ctx.model().getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!isArray(local)) {
                continue;
            }
            CtArrayTypeReference<?> arrayType = (CtArrayTypeReference<?>) local.getType();
            if (arrayType.getDimensionCount() != 1) {
                continue;
            }
            CtTypeReference<?> comp = arrayType.getComponentType();
            CtTypeReference<?> prim = comp.isPrimitive() ? comp : safeUnbox(comp);
            if (prim == null || !LAYOUT_BY_PRIMITIVE.containsKey(prim.getSimpleName())) {
                continue;
            }
            if (!(local.getDefaultExpression() instanceof spoon.reflect.code.CtNewArray<?>)) {
                continue;
            }
            CtMethod<?> method = local.getParent(CtMethod.class);
            if (method == null) {
                continue;
            }
            if (!usagesSupported(method, local, factory)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private List<CtLocalVariable<?>> findCandidates(MutationContext ctx) {
        List<CtLocalVariable<?>> out = new ArrayList<>();
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
            collectCandidatesFromModel(ctx, out);
            return out;
        }
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            collectCandidatesFromElement(hotMethod, ctx, out);
        }
        if (out.isEmpty() && clazz != null) {
            collectCandidatesFromElement(clazz, ctx, out);
        }
        if (out.isEmpty()) {
            collectCandidatesFromModel(ctx, out);
        }
        return out;
    }

    private void collectCandidatesFromElement(CtElement root,
                                              MutationContext ctx,
                                              List<CtLocalVariable<?>> out) {
        if (root == null) {
            return;
        }
        Factory factory = ctx.factory();
        for (CtLocalVariable<?> local : root.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!isArray(local)) {
                continue;
            }
            CtArrayTypeReference<?> arrayType = (CtArrayTypeReference<?>) local.getType();
            if (arrayType.getDimensionCount() != 1) {
                continue;
            }
            CtTypeReference<?> comp = arrayType.getComponentType();
            CtTypeReference<?> prim = comp.isPrimitive() ? comp : safeUnbox(comp);
            if (prim == null || !LAYOUT_BY_PRIMITIVE.containsKey(prim.getSimpleName())) {
                continue;
            }
            if (!(local.getDefaultExpression() instanceof spoon.reflect.code.CtNewArray<?>)) {
                continue; // require explicit new array init
            }
            CtMethod<?> method = local.getParent(CtMethod.class);
            if (method == null) {
                continue;
            }
            if (!usagesSupported(method, local, factory)) {
                continue;
            }
            out.add(local);
        }
    }

    private void collectCandidatesFromModel(MutationContext ctx, List<CtLocalVariable<?>> out) {
        Factory factory = ctx.factory();
        for (CtLocalVariable<?> local : ctx.model().getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!isArray(local)) {
                continue;
            }
            CtArrayTypeReference<?> arrayType = (CtArrayTypeReference<?>) local.getType();
            if (arrayType.getDimensionCount() != 1) {
                continue;
            }
            CtTypeReference<?> comp = arrayType.getComponentType();
            CtTypeReference<?> prim = comp.isPrimitive() ? comp : safeUnbox(comp);
            if (prim == null || !LAYOUT_BY_PRIMITIVE.containsKey(prim.getSimpleName())) {
                continue;
            }
            if (!(local.getDefaultExpression() instanceof spoon.reflect.code.CtNewArray<?>)) {
                continue; // require explicit new array init
            }
            CtMethod<?> method = local.getParent(CtMethod.class);
            if (method == null) {
                continue;
            }
            if (!usagesSupported(method, local, factory)) {
                continue;
            }
            out.add(local);
        }
    }

    private boolean isArray(CtLocalVariable<?> var) {
        return var.getType() instanceof CtArrayTypeReference<?>;
    }

    private CtTypeReference<?> safeUnbox(CtTypeReference<?> ref) {
        try {
            return ref.unbox();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean usagesSupported(CtMethod<?> scope, CtLocalVariable<?> arrayVar, Factory factory) {
        CtLocalVariableReference<?> ref = arrayVar.getReference();

        for (CtVariableAccess<?> access : scope.getElements(new TypeFilter<>(CtVariableAccess.class))) {
            if (!references(access, ref)) {
                continue;
            }
            CtElement parent = access.getParent();
            if (parent instanceof CtFieldRead<?> fr && "length".equals(fr.getVariable().getSimpleName())) {
                continue; // handled later
            }
            if (parent instanceof CtArrayAccess<?, ?> aa && aa.getTarget() instanceof CtVariableAccess<?> va && references(va, ref)) {
                CtExpression<?> idx = aa.getIndexExpression();
                if (idx == null || !isIntLike(idx.getType(), factory)) {
                    return false;
                }
                if (aa instanceof CtArrayWrite) {
                    CtElement p = aa.getParent();
                    if (!(p instanceof CtAssignment<?, ?> assign) || assign.getAssigned() != aa) {
                        return false; // only direct assignment
                    }
                    if (assign instanceof spoon.reflect.code.CtOperatorAssignment<?, ?>) {
                        return false; // skip operator assignments
                    }
                }
                continue;
            }
            // Any other usage (passed to methods, returned, assigned, etc.) is unsupported
            return false;
        }
        return true;
    }

    private boolean isIntLike(CtTypeReference<?> type, Factory factory) {
        if (type == null) {
            return false;
        }
        CtTypeReference<?> prim = type.isPrimitive() ? type : safeUnbox(type);
        if (prim == null) {
            return false;
        }
        String name = prim.getSimpleName();
        return "int".equals(name) || "short".equals(name) || "byte".equals(name) || "char".equals(name);
    }

    private boolean references(CtVariableAccess<?> access, CtLocalVariableReference<?> ref) {
        return access.getVariable() != null && access.getVariable().equals(ref);
    }

    private CtExpression<?> extractLengthExpression(CtLocalVariable<?> arrayVar, Factory factory) {
        if (!(arrayVar.getDefaultExpression() instanceof spoon.reflect.code.CtNewArray<?> na)) {
            return null;
        }
        List<CtExpression<Integer>> dims = na.getDimensionExpressions();
        if (dims != null && !dims.isEmpty()) {
            return dims.get(0);
        }
        List<CtExpression<?>> elems = na.getElements();
        if (elems != null && !elems.isEmpty()) {
            CtLiteral<Integer> lit = factory.Code().createLiteral(elems.size());
            return lit;
        }
        return null;
    }

    private void insertDeclarations(CtLocalVariable<?> original,
                                    CtLocalVariable<?> arenaVar,
                                    CtLocalVariable<?> lenVar,
                                    CtLocalVariable<?> segmentVar) {
        original.insertBefore(arenaVar);
        original.insertBefore(lenVar);
        original.replace(segmentVar);
    }

    private void populateInlineInitializer(CtLocalVariable<?> arrayVar,
                                           String layoutConst,
                                           String segmentName,
                                           Factory factory) {
        if (!(arrayVar.getDefaultExpression() instanceof spoon.reflect.code.CtNewArray<?> na)) {
            return;
        }
        List<CtExpression<?>> elems = na.getElements();
        if (elems == null || elems.isEmpty()) {
            return;
        }
        CtStatement anchor = arrayVar.getParent(CtStatement.class);
        if (anchor == null) {
            return;
        }
        CtBlock<?> block = anchor.getParent(CtBlock.class);
        if (block == null) {
            return;
        }
        int idx = block.getStatements().indexOf(anchor);
        for (int i = 0; i < elems.size(); i++) {
            CtExpression<?> elem = elems.get(i).clone();
            String stmt = segmentName + ".setAtIndex(java.lang.foreign.ValueLayout." + layoutConst + ", " + i + "L, " + elem.toString() + ");";
            block.getStatements().add(idx + 1 + i, factory.Code().createCodeSnippetStatement(stmt));
        }
    }

    private void retargetArrayAccesses(CtMethod<?> scope,
                                       CtLocalVariable<?> arrayVar,
                                       String layoutConst,
                                       String segmentName,
                                       Factory factory,
                                       String lenName) {
        CtLocalVariableReference<?> ref = arrayVar.getReference();

        // Replace length reads first
        for (CtFieldRead<?> fr : new ArrayList<>(scope.getElements(new TypeFilter<CtFieldRead<?>>(CtFieldRead.class)))) {
            if (fr.getTarget() instanceof CtVariableAccess<?> va && references(va, ref) && "length".equals(fr.getVariable().getSimpleName())) {
                fr.replace(factory.Code().createCodeSnippetExpression(lenName));
            }
        }

        // Replace array accesses
        for (CtArrayAccess<?, ?> aa : new ArrayList<>(scope.getElements(new TypeFilter<CtArrayAccess<?, ?>>(CtArrayAccess.class)))) {
            if (!(aa.getTarget() instanceof CtVariableAccess<?> va) || !references(va, ref)) {
                continue;
            }
            CtExpression<?> idx = aa.getIndexExpression();
            String idxSnippet = "(long)(" + idx.clone().toString() + ")";
            if (aa instanceof CtArrayWrite) {
                CtElement parent = aa.getParent();
                if (parent instanceof CtAssignment<?, ?> assign && assign.getAssigned() == aa) {
                    CtExpression<?> rhs = assign.getAssignment().clone();
                    String stmt = segmentName + ".setAtIndex(java.lang.foreign.ValueLayout." + layoutConst + ", " + idxSnippet + ", " + rhs.toString() + ");";
                    CtStatement repl = factory.Code().createCodeSnippetStatement(stmt);
                    assign.replace(repl);
                }
            } else if (aa instanceof CtArrayRead) {
                String expr = segmentName + ".getAtIndex(java.lang.foreign.ValueLayout." + layoutConst + ", " + idxSnippet + ")";
                aa.replace(factory.Code().createCodeSnippetExpression(expr));
            }
        }

        // Update variable access types
        CtTypeReference<?> segmentType = factory.Type().createReference("java.lang.foreign.MemorySegment");
        for (CtVariableAccess<?> access : scope.getElements(new TypeFilter<>(CtVariableAccess.class))) {
            if (references(access, ref)) {
                access.setType(segmentType);
                if (access instanceof CtVariableRead<?> vr && vr.getVariable() != null) {
                    vr.getVariable().setType(segmentType);
                } else if (access instanceof CtVariableWrite<?> vw && vw.getVariable() != null) {
                    vw.getVariable().setType(segmentType);
                }
            }
        }
    }
}
