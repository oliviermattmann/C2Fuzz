package fuzzer.analysis;

import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
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

/*
 * A simple AST printer using Spoon's CtScanner. Helpful to visualize the AST structure and coding of mutators
 */
public class AstTreePrinter extends CtScanner {
    private static final Logger LOGGER = LoggingConfig.getLogger(AstTreePrinter.class);
    private int indent = 0;

    private void printIndent(String text) {
        for (int i = 0; i < indent; i++) {
            //LOGGER.info("--");
            System.out.print("--");
        }
        //LOGGER.info(text);
        System.out.println(text);
    }

    @Override
    public void scan(CtElement element) {
        if (element != null) {
            String details = element.getClass().getSimpleName();

            if (element instanceof CtClass<?> ctClass) {
                details += " : class " + ctClass.getSimpleName();
            } else if (element instanceof CtInterface<?> ctInterface) {
                details += " : interface " + ctInterface.getSimpleName();
            } else if (element instanceof CtMethod<?> ctMethod) {
                details += " : method " + ctMethod.getSimpleName() +
                           "(" + ctMethod.getParameters().stream()
                                 .map(p -> p.getType().getSimpleName() + " " + p.getSimpleName())
                                 .reduce((a, b) -> a + ", " + b).orElse("") + ")" +
                           " : returns " + (ctMethod.getType() != null ? ctMethod.getType().getSimpleName() : "void");
            } else if (element instanceof CtConstructor<?> ctConstructor) {
                details += " : constructor " + ctConstructor.getSimpleName() +
                           "(" + ctConstructor.getParameters().stream()
                                 .map(p -> p.getType().getSimpleName() + " " + p.getSimpleName())
                                 .reduce((a, b) -> a + ", " + b).orElse("") + ")";
            } else if (element instanceof CtField<?> ctField) {
                details += " : field " + ctField.getSimpleName() +
                           " : type " + (ctField.getType() != null ? ctField.getType().getSimpleName() : "?");
            } else if (element instanceof CtParameter<?> ctParam) {
                details += " : parameter " + ctParam.getSimpleName() +
                           " : type " + (ctParam.getType() != null ? ctParam.getType().getSimpleName() : "?");
            } else if (element instanceof CtLocalVariable<?> ctVar) {
                details += " : local " + ctVar.getSimpleName() +
                           " : type " + (ctVar.getType() != null ? ctVar.getType().getSimpleName() : "?");
            } else if (element instanceof CtVariableRead<?> ctVarRead) {
                details += " : read " + (ctVarRead.getVariable() != null ? ctVarRead.getVariable().getSimpleName() : "?");
            } else if (element instanceof CtInvocation<?> ctInvocation) {
                details += " : invocation of " + ctInvocation.getExecutable().getSimpleName();
            } else if (element instanceof CtReturn<?> ctReturn) {
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
    }

    public void print(CtModel model) {
        model.getRootPackage().accept(this);
    }

    public void printSubAST(CtElement node) {
        node.accept(this); // "this" is your CtScanner-based printer
    }


}
