package fuzzer.mutators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtArrayRead;
import spoon.reflect.code.CtArrayWrite;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Mirrors primitive array usage with a MemorySegment view backed by MemorySegment.ofArray.
 * The original array remains untouched; reads/writes are duplicated to exercise foreign memory paths.
 */
public class ArrayMemorySegmentShadowMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(ArrayMemorySegmentShadowMutator.class);

    private static final Map<String, String> LAYOUT_BY_PRIMITIVE = new HashMap<>();
    static {
        LAYOUT_BY_PRIMITIVE.put("byte", "JAVA_BYTE");
        LAYOUT_BY_PRIMITIVE.put("short", "JAVA_SHORT");
        LAYOUT_BY_PRIMITIVE.put("char", "JAVA_CHAR");
        LAYOUT_BY_PRIMITIVE.put("int", "JAVA_INT");
        LAYOUT_BY_PRIMITIVE.put("long", "JAVA_LONG");
        LAYOUT_BY_PRIMITIVE.put("float", "JAVA_FLOAT");
        LAYOUT_BY_PRIMITIVE.put("double", "JAVA_DOUBLE");
    }

    public ArrayMemorySegmentShadowMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        Factory factory = ctx.factory();
        List<CtLocalVariable<?>> candidates = findCandidates(ctx);
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No eligible arrays to mirror");
        }

        CtLocalVariable<?> arrayVar = candidates.get(random.nextInt(candidates.size()));
        CtArrayTypeReference<?> arrayType = (CtArrayTypeReference<?>) arrayVar.getType();
        CtTypeReference<?> component = arrayType.getComponentType();
        CtTypeReference<?> primitive = component.isPrimitive() ? component : safeUnbox(component);
        String layoutConst = LAYOUT_BY_PRIMITIVE.get(primitive.getSimpleName());
        if (layoutConst == null) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Unsupported component type");
        }

        CtMethod<?> method = arrayVar.getParent(CtMethod.class);
        if (method == null) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Array not inside a method");
        }
        CtBlock<?> block = arrayVar.getParent(CtBlock.class);
        if (block == null) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "Cannot insert shadow variables");
        }

        String baseName = arrayVar.getSimpleName();
        String segName = uniqueName(baseName, "Seg", method);
        String lenName = uniqueName(baseName, "Len", method);

        CtTypeReference<?> segmentType = factory.Type().createReference("java.lang.foreign.MemorySegment");
        CtLocalVariable<?> segmentVar = factory.Code().createLocalVariable(
                segmentType,
                segName,
                factory.Code().createCodeSnippetExpression("java.lang.foreign.MemorySegment.ofArray(" + baseName + ")"));
        CtLocalVariable<?> lenVar = factory.Code().createLocalVariable(
                factory.Type().INTEGER_PRIMITIVE,
                lenName,
                factory.Code().createCodeSnippetExpression(baseName + ".length"));

        insertAfter(arrayVar, block, segmentVar, lenVar);

        Map<CtStatement, Integer> insertionOffsets = new IdentityHashMap<>();
        int lengthRewrites = rewriteLengthReads(method, arrayVar, lenName, factory);
        MirrorStats writeStats = mirrorWrites(method, arrayVar, layoutConst, segName, factory, insertionOffsets);
        MirrorStats readStats = mirrorReads(method, arrayVar, layoutConst, segName, factory, insertionOffsets);

        LOGGER.fine(() -> String.format("Shadowed array %s as MemorySegment (%s). writes=%d skippedWrites=%d reads=%d skippedReads=%d lengthRewrites=%d",
                baseName, layoutConst, writeStats.mirrored, writeStats.skipped, readStats.mirrored, readStats.skipped, lengthRewrites));

        String detail = String.format("Mirrored writes=%d (skipped=%d), reads=%d (skipped=%d), length rewrites=%d",
                writeStats.mirrored, writeStats.skipped, readStats.mirrored, readStats.skipped, lengthRewrites);
        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), detail);
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        return hasCandidates(ctx);
    }

    private boolean hasCandidates(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && hasCandidatesInElement(method)) {
                return true;
            }
            if (hasCandidatesInElement(clazz)) {
                return true;
            }
        }
        return hasCandidatesInModel(ctx);
    }

    private boolean hasCandidatesInElement(CtElement root) {
        if (root == null) {
            return false;
        }
        for (CtLocalVariable<?> local : root.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!(local.getType() instanceof CtArrayTypeReference<?> arrayType)) {
                continue;
            }
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
            if (local.getParent(CtBlock.class) == null) {
                continue;
            }
            if (isReassigned(method, local)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean hasCandidatesInModel(MutationContext ctx) {
        for (CtLocalVariable<?> local : ctx.model().getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!(local.getType() instanceof CtArrayTypeReference<?> arrayType)) {
                continue;
            }
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
            if (local.getParent(CtBlock.class) == null) {
                continue;
            }
            if (isReassigned(method, local)) {
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
            collectCandidatesFromElement(hotMethod, out);
        }
        if (out.isEmpty() && clazz != null) {
            collectCandidatesFromElement(clazz, out);
        }
        if (out.isEmpty()) {
            collectCandidatesFromModel(ctx, out);
        }
        return out;
    }

    private void collectCandidatesFromElement(CtElement root, List<CtLocalVariable<?>> out) {
        if (root == null) {
            return;
        }
        for (CtLocalVariable<?> local : root.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!(local.getType() instanceof CtArrayTypeReference<?> arrayType)) {
                continue;
            }
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
            if (local.getParent(CtBlock.class) == null) {
                continue;
            }
            if (isReassigned(method, local)) {
                continue;
            }
            out.add(local);
        }
    }

    private void collectCandidatesFromModel(MutationContext ctx, List<CtLocalVariable<?>> out) {
        for (CtLocalVariable<?> local : ctx.model().getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!(local.getType() instanceof CtArrayTypeReference<?> arrayType)) {
                continue;
            }
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
            if (local.getParent(CtBlock.class) == null) {
                continue;
            }
            if (isReassigned(method, local)) {
                continue;
            }
            out.add(local);
        }
    }

    private boolean isReassigned(CtMethod<?> scope, CtLocalVariable<?> arrayVar) {
        CtLocalVariableReference<?> ref = arrayVar.getReference();
        for (CtAssignment<?, ?> assign : scope.getElements(new TypeFilter<>(CtAssignment.class))) {
            if (assign.getAssigned() instanceof CtVariableWrite<?> vw && references(vw, ref)) {
                return true;
            }
        }
        return false;
    }

    private CtTypeReference<?> safeUnbox(CtTypeReference<?> ref) {
        try {
            return ref.unbox();
        } catch (Exception ex) {
            return null;
        }
    }

    private void insertAfter(CtLocalVariable<?> anchor,
                             CtBlock<?> block,
                             CtLocalVariable<?> first,
                             CtLocalVariable<?> second) {
        int idx = block.getStatements().indexOf(anchor);
        if (idx < 0) {
            return;
        }
        block.getStatements().add(idx + 1, first);
        block.getStatements().add(idx + 2, second);
    }

    private int rewriteLengthReads(CtMethod<?> scope,
                                   CtLocalVariable<?> arrayVar,
                                   String lenName,
                                   Factory factory) {
        CtLocalVariableReference<?> ref = arrayVar.getReference();
        int rewrites = 0;
        for (CtFieldRead<?> fr : new ArrayList<>(scope.getElements(new TypeFilter<CtFieldRead<?>>(CtFieldRead.class)))) {
            if (fr.getTarget() instanceof CtVariableAccess<?> va && references(va, ref) && "length".equals(fr.getVariable().getSimpleName())) {
                fr.replace(factory.Code().createCodeSnippetExpression(lenName));
                rewrites++;
            }
        }
        return rewrites;
    }

    private MirrorStats mirrorWrites(CtMethod<?> scope,
                                     CtLocalVariable<?> arrayVar,
                                     String layoutConst,
                                     String segmentName,
                                     Factory factory,
                                     Map<CtStatement, Integer> insertionOffsets) {
        CtLocalVariableReference<?> ref = arrayVar.getReference();
        int mirrored = 0;
        int skipped = 0;
        for (CtArrayAccess<?, ?> aa : new ArrayList<>(scope.getElements(new TypeFilter<CtArrayAccess<?, ?>>(CtArrayAccess.class)))) {
            if (!(aa instanceof CtArrayWrite)) {
                continue;
            }
            if (!(aa.getTarget() instanceof CtVariableAccess<?> va) || !references(va, ref)) {
                continue;
            }
            CtElement parent = aa.getParent();
            if (!(parent instanceof CtAssignment<?, ?> assign) || assign.getAssigned() != aa) {
                skipped++;
                continue;
            }
            CtExpression<?> idx = aa.getIndexExpression();
            if (idx == null) {
                skipped++;
                continue;
            }
            CtExpression<?> rhs = assign.getAssignment();
            String idxSnippet = "(long)(" + idx.clone().toString() + ")";
            String stmt = segmentName + ".setAtIndex(java.lang.foreign.ValueLayout." + layoutConst + ", " + idxSnippet + ", " + rhs.clone().toString() + ");";
            CtStatement anchor = assign.getParent(CtStatement.class);
            if (anchor != null && insertAfter(anchor, factory.Code().createCodeSnippetStatement(stmt), insertionOffsets)) {
                mirrored++;
            } else {
                skipped++;
            }
        }
        return new MirrorStats(mirrored, skipped);
    }

    private MirrorStats mirrorReads(CtMethod<?> scope,
                                    CtLocalVariable<?> arrayVar,
                                    String layoutConst,
                                    String segmentName,
                                    Factory factory,
                                    Map<CtStatement, Integer> insertionOffsets) {
        CtLocalVariableReference<?> ref = arrayVar.getReference();
        int mirrored = 0;
        int skipped = 0;
        for (CtArrayAccess<?, ?> aa : new ArrayList<>(scope.getElements(new TypeFilter<CtArrayAccess<?, ?>>(CtArrayAccess.class)))) {
            if (!(aa instanceof CtArrayRead)) {
                continue;
            }
            if (!(aa.getTarget() instanceof CtVariableAccess<?> va) || !references(va, ref)) {
                continue;
            }
            CtExpression<?> idx = aa.getIndexExpression();
            if (idx == null) {
                skipped++;
                continue;
            }
            String idxSnippet = "(long)(" + idx.clone().toString() + ")";
            String stmt = segmentName + ".getAtIndex(java.lang.foreign.ValueLayout." + layoutConst + ", " + idxSnippet + ");";
            CtStatement anchor = aa.getParent(CtStatement.class);
            if (anchor != null && insertAfter(anchor, factory.Code().createCodeSnippetStatement(stmt), insertionOffsets)) {
                mirrored++;
            } else {
                skipped++;
            }
        }
        return new MirrorStats(mirrored, skipped);
    }

    private boolean insertAfter(CtStatement anchor,
                                CtStatement toInsert,
                                Map<CtStatement, Integer> insertionOffsets) {
        CtBlock<?> block = anchor.getParent(CtBlock.class);
        if (block == null) {
            return false;
        }
        int idx = block.getStatements().indexOf(anchor);
        if (idx < 0) {
            return false;
        }
        int offset = insertionOffsets.getOrDefault(anchor, 0) + 1;
        insertionOffsets.put(anchor, offset);
        block.getStatements().add(idx + offset, toInsert);
        return true;
    }

    private String uniqueName(String base, String suffix, CtMethod<?> scope) {
        String candidate = base + suffix;
        int counter = 0;
        while (nameExists(scope, candidate)) {
            counter++;
            candidate = base + suffix + counter;
        }
        return candidate;
    }

    private boolean nameExists(CtMethod<?> scope, String name) {
        return scope.getElements(new TypeFilter<>(CtLocalVariable.class))
                .stream()
                .anyMatch(v -> name.equals(v.getSimpleName()));
    }

    private boolean references(CtVariableAccess<?> access, CtLocalVariableReference<?> ref) {
        return access.getVariable() != null && access.getVariable().equals(ref);
    }

    private static class MirrorStats {
        final int mirrored;
        final int skipped;

        MirrorStats(int mirrored, int skipped) {
            this.mirrored = mirrored;
            this.skipped = skipped;
        }
    }
}
