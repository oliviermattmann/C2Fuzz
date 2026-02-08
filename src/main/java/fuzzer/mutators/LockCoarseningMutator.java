package fuzzer.mutators;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import fuzzer.logging.LoggingConfig;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtSynchronized;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import java.util.stream.Collectors;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;


public class LockCoarseningMutator implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LockCoarseningMutator.class);

    public LockCoarseningMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {

        Factory factory = ctx.factory();
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> hotMethod = ctx.targetMethod();
        if (clazz == null) {
            List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
            if (classes.isEmpty()) {
                return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No classes found");
            }
            clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));
            hotMethod = null;
            LOGGER.fine("No hot class provided; selected random class " + clazz.getQualifiedName());
        }


        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        // find existing synchronized blocks
        List<CtSynchronized> syncBlocks = new java.util.ArrayList<>();
        boolean exploreWholeModel = random.nextDouble() < 0.2;
        if (exploreWholeModel) {
            LOGGER.fine("Exploration mode active; scanning entire model for lock-coarsening candidates");
            collectSynchronizedBlocksFromModel(ctx, syncBlocks);
        } else {
            if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
                LOGGER.fine("Collecting lock-coarsening candidates from hot method " + hotMethod.getSimpleName());
                collectSynchronizedBlocks(hotMethod, syncBlocks);
            }
            if (syncBlocks.isEmpty()) {
                if (hotMethod != null) {
                    LOGGER.fine("No lock-coarsening candidates found in hot method; falling back to class scan");
                } else {
                    LOGGER.fine("No hot method available; scanning entire class for lock-coarsening candidates");
                }
                collectSynchronizedBlocks(clazz, syncBlocks);
            }
            if (syncBlocks.isEmpty()) {
                LOGGER.fine("No lock-coarsening candidates in class; scanning entire model");
                collectSynchronizedBlocksFromModel(ctx, syncBlocks);
            }
        }
        if (syncBlocks.isEmpty()) {
            LOGGER.fine("No synchronized blocks found for LockCoarseningMutator.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No synchronized blocks found for LockCoarseningMutator");
        }


        CtSynchronized chosen = syncBlocks.get(random.nextInt(syncBlocks.size()));
        CtBlock<?> body = chosen.getBlock();
        if (!(body instanceof CtBlock<?> block) || block.getStatements().size() < 2 || hasControlHazards(block)) {
            LOGGER.fine("Selected synchronized block is not splittable; skipping");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No splittable synchronized blocks");
        }

        List<CtStatement> statements = block.getStatements();
        boolean[] splitAllowed = new boolean[statements.size() - 1];
        for (int i = 0; i < splitAllowed.length; i++) {
            splitAllowed[i] = !referencesLater(statements.get(i), statements, i + 1);
        }
        java.util.List<Integer> validSplits = new java.util.ArrayList<>();
        for (int i = 0; i < splitAllowed.length; i++) {
            if (splitAllowed[i]) {
                validSplits.add(i + 1);
            }
        }
        if (validSplits.isEmpty()) {
            LOGGER.fine("No safe split point found inside synchronized block; skipping");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No splittable synchronized blocks");
        }
        int splitIndex = validSplits.get(random.nextInt(validSplits.size()));
        CtBlock<?> firstBlock = factory.Core().createBlock();
        for (int i = 0; i < splitIndex; i++) {
            firstBlock.addStatement(block.getStatements().get(i).clone());
        }
        CtBlock<?> secondBlock = factory.Core().createBlock();
        for (int i = splitIndex; i < block.getStatements().size(); i++) {
            secondBlock.addStatement(block.getStatements().get(i).clone());
        }

        CtExpression<?> expr = chosen.getExpression().clone();
        CtSynchronized sync1 = factory.Core().createSynchronized();
        sync1.setExpression(expr.clone());
        sync1.setBlock(firstBlock);

        CtSynchronized sync2 = factory.Core().createSynchronized();
        sync2.setExpression(expr.clone());
        sync2.setBlock(secondBlock);

        chosen.replace(sync1);
        sync1.insertAfter(sync2);

        MutationResult result = new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
        return result;
    } 

    @Override
    public boolean isApplicable(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && hasSynchronizedBlock(method)) {
                return true;
            }
            if (hasSynchronizedBlock(clazz)) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (hasSynchronizedBlock(c)) {
                return true;
            }
        }
        return false;
    }

    private void collectSynchronizedBlocks(CtElement root, List<CtSynchronized> syncBlocks) {
        if (root == null) {
            return;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtSynchronized)) {
            syncBlocks.add((CtSynchronized) element);
        }
    }

    private void collectSynchronizedBlocksFromModel(MutationContext ctx, List<CtSynchronized> syncBlocks) {
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        for (CtElement element : classes) {
            collectSynchronizedBlocks((CtClass<?>) element, syncBlocks);
        }
    }

    private boolean hasSynchronizedBlock(CtElement root) {
        return root != null && !root.getElements(e -> e instanceof CtSynchronized).isEmpty();
    }

    private boolean hasControlHazards(CtBlock<?> block) {
        return block.getStatements().stream().anyMatch(stmt ->
            stmt instanceof spoon.reflect.code.CtReturn ||
            stmt instanceof spoon.reflect.code.CtBreak ||
            stmt instanceof spoon.reflect.code.CtContinue ||
            stmt instanceof spoon.reflect.code.CtThrow
        );
    }

    private boolean referencesLater(CtStatement statement, List<CtStatement> statements, int startIndex) {
        List<CtLocalVariable<?>> locals = statement.getElements(e -> e instanceof CtLocalVariable<?> )
            .stream()
            .map(e -> (CtLocalVariable<?>) e)
            .collect(Collectors.toList());
        if (locals.isEmpty()) {
            return false;
        }
        for (int i = startIndex; i < statements.size(); i++) {
            CtStatement later = statements.get(i);
            for (CtLocalVariable<?> local : locals) {
                boolean used = !later.getElements(e -> e instanceof CtVariableRead<?> && ((CtVariableRead<?>) e).getVariable().equals(local.getReference())).isEmpty()
                    || !later.getElements(e -> e instanceof CtVariableWrite<?> && ((CtVariableWrite<?>) e).getVariable().equals(local.getReference())).isEmpty();
                if (used) {
                    return true;
                }
            }
        }
        return false;
    }
}
