package fuzzer.mutators;

import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtVariableReference;

final class LocalVariableFactory {
    private final Factory factory;

    LocalVariableFactory(Factory factory) {
        this.factory = factory;
    }

    IntVar newInt(String name, int initialValue) {
        CtLocalVariable<Integer> decl = factory.Code().createLocalVariable(
                factory.Type().integerPrimitiveType(),
                name,
                factory.Code().createLiteral(initialValue));
        return new IntVar(decl);
    }

    BooleanVar newBoolean(String name, String initializerSnippet) {
        CtExpression<Boolean> initializer = factory.Code().createCodeSnippetExpression(initializerSnippet);
        CtLocalVariable<Boolean> decl = factory.Code().createLocalVariable(
                factory.Type().createReference(boolean.class),
                name,
                initializer);
        return new BooleanVar(decl);
    }

    record IntVar(CtLocalVariable<Integer> declaration) {
        String name() {
            return declaration.getSimpleName();
        }

        CtVariableReference<Integer> reference() {
            return declaration.getReference();
        }
    }

    record BooleanVar(CtLocalVariable<Boolean> declaration) {
        String name() {
            return declaration.getSimpleName();
        }

        CtVariableReference<Boolean> reference() {
            return declaration.getReference();
        }
    }
}
