package com.example.spoonplayground;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.visitor.CtScanner;

/**
 * Indented AST printer based on Spoon's CtScanner.
 */
class AstTreePrinter extends CtScanner {
    private int indent = 0;

    private void printIndent(String text) {
        for (int i = 0; i < indent; i++) {
            System.out.print("--");
        }
        System.out.println(text);
    }

    @Override
    public void scan(CtElement element) {
        if (element == null) {
            return;
        }
        String details = element.getClass().getSimpleName();

        if (element instanceof CtClass<?> ctClass) {
            details += " : class " + ctClass.getSimpleName();
        } else if (element instanceof CtInterface<?> ctInterface) {
            details += " : interface " + ctInterface.getSimpleName();
        } else if (element instanceof CtMethod<?> ctMethod) {
            String params = ctMethod.getParameters().stream()
                    .map(p -> p.getType().getSimpleName() + " " + p.getSimpleName())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            String returnType = (ctMethod.getType() != null) ? ctMethod.getType().getSimpleName() : "void";
            details += " : method " + ctMethod.getSimpleName() + "(" + params + ")" + " : returns " + returnType;
        } else if (element instanceof CtConstructor<?> ctConstructor) {
            String params = ctConstructor.getParameters().stream()
                    .map(p -> p.getType().getSimpleName() + " " + p.getSimpleName())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            details += " : constructor " + ctConstructor.getSimpleName() + "(" + params + ")";
        } else if (element instanceof CtField<?> ctField) {
            String type = (ctField.getType() != null) ? ctField.getType().getSimpleName() : "?";
            details += " : field " + ctField.getSimpleName() + " : type " + type;
        } else if (element instanceof CtParameter<?> ctParam) {
            String type = (ctParam.getType() != null) ? ctParam.getType().getSimpleName() : "?";
            details += " : parameter " + ctParam.getSimpleName() + " : type " + type;
        } else if (element instanceof CtLocalVariable<?> ctVar) {
            String type = (ctVar.getType() != null) ? ctVar.getType().getSimpleName() : "?";
            details += " : local " + ctVar.getSimpleName() + " : type " + type;
        } else if (element instanceof CtVariableRead<?> ctVarRead) {
            String variable = (ctVarRead.getVariable() != null) ? ctVarRead.getVariable().getSimpleName() : "?";
            details += " : read " + variable;
        } else if (element instanceof CtInvocation<?> ctInvocation) {
            details += " : invocation of " + ctInvocation.getExecutable().getSimpleName();
        } else if (element instanceof CtReturn<?>) {
            details += " : return";
        } else if (element instanceof CtBinaryOperator<?> ctOp) {
            details += " : operator " + ctOp.getKind();
        } else if (element instanceof CtLiteral<?> ctLiteral) {
            details += " : literal " + ctLiteral.getValue();
        } else {
            details += " : " + element.getShortRepresentation();
        }

        printIndent(details);
        indent++;
        super.scan(element);
        indent--;
    }

    void print(CtModel model) {
        if (model != null && model.getRootPackage() != null) {
            model.getRootPackage().accept(this);
        }
    }

    void printSubAST(CtElement node) {
        if (node != null) {
            node.accept(this);
        }
    }
}
