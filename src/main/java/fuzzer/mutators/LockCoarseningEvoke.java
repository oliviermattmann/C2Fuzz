package fuzzer.mutators;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtSynchronized;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.Factory;


public class LockCoarseningEvoke implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LockCoarseningEvoke.class);

    public LockCoarseningEvoke(Random random) {
        this.random = random;
    }

    @Override
    public Launcher mutate(Launcher launcher, CtModel model, Factory factory) {
        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return null;
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));


        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        // find existing synchronized blocks
        List<CtSynchronized> syncBlocks = clazz.getElements(e -> e instanceof CtSynchronized);
        if (syncBlocks.isEmpty()) {
            LOGGER.fine("No synchronized blocks found for LockCoarseningEvoke.");
            return null;
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

        return launcher;
    } 
}
