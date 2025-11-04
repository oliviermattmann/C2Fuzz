package fuzzer.mutators;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtSynchronized;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;


public class LockCoarseningEvoke implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LockCoarseningEvoke.class);

    public LockCoarseningEvoke(Random random) {
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
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            LOGGER.fine("Collecting lock-coarsening candidates from hot method " + hotMethod.getSimpleName());
            hotMethod.getElements(e -> e instanceof CtSynchronized).forEach(e -> syncBlocks.add((CtSynchronized) e));
        }
        if (syncBlocks.isEmpty()) {
            if (hotMethod != null) {
                LOGGER.fine("No lock-coarsening candidates found in hot method; falling back to class scan");
            } else {
                LOGGER.fine("No hot method available; scanning entire class for lock-coarsening candidates");
            }
            clazz.getElements(e -> e instanceof CtSynchronized).forEach(e -> syncBlocks.add((CtSynchronized) e));
        }
        if (syncBlocks.isEmpty()) {
            LOGGER.fine("No synchronized blocks found for LockCoarseningEvoke.");
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No synchronized blocks found for LockCoarseningEvoke");
        }


        // 
        CtSynchronized chosen = syncBlocks.get(random.nextInt(syncBlocks.size()));

        // TODO actually split the block, not just clone it
        CtExpression<?> expr = chosen.getExpression().clone(); // same monitor
        CtSynchronized sync1 = factory.Core().createSynchronized();
        sync1.setExpression(expr.clone());
        sync1.setBlock(factory.Core().createBlock()); // first part of sync body

        CtSynchronized sync2 = factory.Core().createSynchronized();
        sync2.setExpression(expr.clone());
        sync2.setBlock((CtBlock<?>) chosen.getBlock().clone()); // here the the rest of the body

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
            if (method != null && method.getDeclaringType() == clazz) {
                boolean methodHasSync = !method.getElements(e -> e instanceof CtSynchronized).isEmpty();
                if (methodHasSync) {
                    return true;
                }
            }
            boolean classHasSync = !clazz.getElements(e -> e instanceof CtSynchronized).isEmpty();
            if (classHasSync) {
                return true;
            }
        }
        List<CtElement> classes = ctx.model().getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return false;
        }
        for (CtElement element : classes) {
            CtClass<?> c = (CtClass<?>) element;
            if (!c.getElements(e -> e instanceof CtSynchronized).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
