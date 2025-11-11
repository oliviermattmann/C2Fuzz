package fuzzer.mutators;

import java.util.EnumSet;

import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.declaration.ModifierKind;

final class OpaqueToggleSupport {
    private static final String FIELD_NAME = "_mutatorToggle";
    private static final String METHOD_NAME = "_mutatorFlip";

    private OpaqueToggleSupport() {}

    static String booleanFlipSnippet(CtElement anchor, Factory factory) {
        CtClass<?> owningClass = anchor.getParent(CtClass.class);
        if (owningClass == null) {
            // Fallback: still avoid constant folding by referencing a fresh object hash
            return "((System.identityHashCode(new Object()) & 1) == 0)";
        }
        ensureToggleField(owningClass, factory);
        ensureToggleMethod(owningClass, factory);
        return METHOD_NAME + "()";
    }

    private static void ensureToggleField(CtClass<?> clazz, Factory factory) {
        if (findToggleField(clazz) != null) {
            return;
        }
        CtTypeReference<Boolean> boolType = factory.Type().BOOLEAN_PRIMITIVE;
        CtField<Boolean> field = factory.Field().create(clazz,
                EnumSet.of(ModifierKind.PRIVATE, ModifierKind.STATIC, ModifierKind.VOLATILE),
                boolType,
                FIELD_NAME,
                factory.Code().createLiteral(false));
        clazz.addField(field);
    }

    @SuppressWarnings("unchecked")
    private static void ensureToggleMethod(CtClass<?> clazz, Factory factory) {
        boolean exists = clazz.getMethods().stream()
                .anyMatch(m -> METHOD_NAME.equals(m.getSimpleName()) && m.getParameters().isEmpty());
        if (exists) {
            return;
        }
        CtField<Boolean> field = findToggleField(clazz);
        if (field == null) {
            return;
        }
        CtTypeReference<Boolean> boolType = factory.Type().BOOLEAN_PRIMITIVE;
        CtMethod<Boolean> method = factory.Method().create(clazz,
                EnumSet.of(ModifierKind.PRIVATE, ModifierKind.STATIC),
                boolType,
                METHOD_NAME,
                java.util.List.of(),
                java.util.Set.of());

        CtBlock<Boolean> body = factory.Core().createBlock();
        CtStatement toggle = factory.Code().createCodeSnippetStatement(
                FIELD_NAME + " = !" + FIELD_NAME + ";");
        body.addStatement(toggle);

        CtReturn<Boolean> ret = factory.Core().createReturn();
        ret.setReturnedExpression(factory.Code().createCodeSnippetExpression(FIELD_NAME));
        body.addStatement(ret);

        method.setBody(body);
    }

    private static CtField<Boolean> findToggleField(CtClass<?> clazz) {
        for (CtField<?> field : clazz.getFields()) {
            if (FIELD_NAME.equals(field.getSimpleName())) {
                @SuppressWarnings("unchecked")
                CtField<Boolean> typed = (CtField<Boolean>) field;
                return typed;
            }
        }
        return null;
    }
}
