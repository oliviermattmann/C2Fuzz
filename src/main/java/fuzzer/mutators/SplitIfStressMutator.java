package fuzzer.mutators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;

public class SplitIfStressMutator implements Mutator {
    private final Random random;

    public SplitIfStressMutator(Random random) {
        this.random = random;
    }

    @Override
    public MutationResult mutate(MutationContext ctx) {
        List<CtIf> candidates = collectIfs(ctx);
        if (candidates.isEmpty()) {
            return new MutationResult(MutationStatus.SKIPPED, ctx.launcher(), "No eligible if-statements");
        }
        CtIf original = candidates.get(random.nextInt(candidates.size()));
        applySplit(ctx.factory(), original);
        return new MutationResult(MutationStatus.SUCCESS, ctx.launcher(), "");
    }

    @Override
    public boolean isApplicable(MutationContext ctx) {
        return hasIfs(ctx);
    }

    private boolean hasIfs(MutationContext ctx) {
        CtClass<?> clazz = ctx.targetClass();
        CtMethod<?> method = ctx.targetMethod();
        if (clazz != null) {
            if (method != null && method.getDeclaringType() == clazz && hasIfsInElement(method)) {
                return true;
            }
            if (hasIfsInElement(clazz)) {
                return true;
            }
        }
        return hasIfsInModel(ctx);
    }

    private boolean hasIfsInElement(CtElement root) {
        if (root == null) {
            return false;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtIf)) {
            CtIf ctIf = (CtIf) element;
            if (ctIf.getElseStatement() != null && ctIf.getThenStatement() != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasIfsInModel(MutationContext ctx) {
        for (CtElement element : ctx.model().getElements(e -> e instanceof CtIf)) {
            CtIf ctIf = (CtIf) element;
            if (ctIf.getElseStatement() != null && ctIf.getThenStatement() != null) {
                return true;
            }
        }
        return false;
    }

    private List<CtIf> collectIfs(MutationContext ctx) {
        List<CtIf> result = new ArrayList<>();
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
            collectIfsFromModel(ctx, result);
            return result;
        }
        if (hotMethod != null && hotMethod.getDeclaringType() == clazz) {
            collectIfsFromElement(hotMethod, result);
        }
        if (result.isEmpty() && clazz != null) {
            collectIfsFromElement(clazz, result);
        }
        if (result.isEmpty()) {
            collectIfsFromModel(ctx, result);
        }
        return result;
    }

    private void collectIfsFromElement(CtElement root, List<CtIf> result) {
        if (root == null) {
            return;
        }
        for (CtElement element : root.getElements(e -> e instanceof CtIf)) {
            CtIf ctIf = (CtIf) element;
            if (ctIf.getElseStatement() != null && ctIf.getThenStatement() != null) {
                result.add(ctIf);
            }
        }
    }

    private void collectIfsFromModel(MutationContext ctx, List<CtIf> result) {
        for (CtElement element : ctx.model().getElements(e -> e instanceof CtIf)) {
            CtIf ctIf = (CtIf) element;
            if (ctIf.getElseStatement() != null && ctIf.getThenStatement() != null) {
                result.add(ctIf);
            }
        }
    }

    private void applySplit(Factory factory, CtIf original) {
        CtIf innerThen = factory.Core().createIf();
        innerThen.setCondition(original.getCondition().clone());
        innerThen.setThenStatement(cloneAsBlock(factory, original.getThenStatement()));
        innerThen.setElseStatement(cloneAsBlock(factory, original.getElseStatement()));

        CtIf innerElse = factory.Core().createIf();
        innerElse.setCondition(original.getCondition().clone());
        innerElse.setThenStatement(cloneAsBlock(factory, original.getElseStatement()));
        innerElse.setElseStatement(cloneAsBlock(factory, original.getThenStatement()));

        CtIf outer = factory.Core().createIf();
        outer.setCondition(original.getCondition().clone());
        outer.setThenStatement(innerThen);
        outer.setElseStatement(innerElse);

        original.replace(outer);
    }

    private CtStatement cloneAsBlock(Factory factory, CtStatement statement) {
        CtBlock<?> block = factory.Core().createBlock();
        block.addStatement(statement.clone());
        return block;
    }
}
