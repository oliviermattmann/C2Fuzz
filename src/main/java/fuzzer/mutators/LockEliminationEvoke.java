package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtSynchronized;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class LockEliminationEvoke implements Mutator {
    private final Random random;
    private static final Logger LOGGER = LoggingConfig.getLogger(LockEliminationEvoke.class);

    public LockEliminationEvoke(Random random) {
        this.random = random;
    }

    @Override
    public Launcher mutate(Launcher launcher, CtModel model, Factory factory) {
        // Find a public class
        // get a random class
        List<CtElement> classes = model.getElements(e -> e instanceof CtClass<?>);
        if (classes.isEmpty()) {
            return null;
        }
        CtClass<?> clazz = (CtClass<?>) classes.get(random.nextInt(classes.size()));


        LOGGER.fine("Mutating class: " + clazz.getSimpleName());

        // Collect assignment candidates
        List<CtAssignment<?, ?>> candidates = new ArrayList<>(
            clazz.getElements(e -> e instanceof CtAssignment)
        );
        if (candidates.isEmpty()) {
            LOGGER.fine("No assignments found for LockEliminationEvoke.");
            return null;
        }


        CtAssignment<?, ?> chosen = candidates.get(random.nextInt(candidates.size()));
        CtMethod<?> parentMethod = chosen.getParent(CtMethod.class);
        boolean inStaticContext = parentMethod != null && parentMethod.isStatic();

        CtExpression<?> lockExpr;
        if (inStaticContext) {
            lockExpr = makeClassLiteral(factory, clazz.getReference());
        }
        else {
            lockExpr = factory.Code().createThisAccess((CtTypeReference) clazz.getReference());
        }
        CtSynchronized sync = factory.Core().createSynchronized();
        sync.setExpression(lockExpr);
        CtBlock<?> body = factory.Core().createBlock();
        body.addStatement(chosen.clone());
        sync.setBlock(body);

        chosen.replace(sync);
        return launcher;
    }

    private static CtExpression<?> makeClassLiteral(Factory f, CtTypeReference<?> tref) {
        // Try Spoon convenience (present in newer Spoon)
        try {
            return (CtExpression<?>) f.Code().getClass()
                .getMethod("createClassAccess", CtTypeReference.class)
                .invoke(f.Code(), tref);
        } catch (Exception e) {
            LOGGER.warning("Failed to use create Class literal");
            return null;
        }

    }

    
}
