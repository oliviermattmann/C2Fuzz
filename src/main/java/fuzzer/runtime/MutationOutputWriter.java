package fuzzer.runtime;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.io.FileManager;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.TestCase;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.visitor.filter.TypeFilter;

final class MutationOutputWriter {

    private static final Logger LOGGER = LoggingConfig.getLogger(MutationOutputWriter.class);

    private final FileManager fileManager;

    MutationOutputWriter(FileManager fileManager) {
        this.fileManager = Objects.requireNonNull(fileManager, "fileManager");
    }

    TestCase finalizeMutation(String parentName, CtModel model, Launcher launcher, TestCase testCase) {
        CtType<?> topLevelType = model.getElements(new TypeFilter<>(CtType.class)).stream()
                .filter(CtType::isTopLevel)
                .findFirst()
                .orElseThrow();

        String mutatedSource;
        try {
            mutatedSource = printWithSniper(launcher.getFactory(), topLevelType);
        } catch (IllegalStateException ex) {
            LOGGER.log(Level.WARNING,
                    "Sniper pretty-printer failed for {0} using mutator {1}: {2}",
                    new Object[]{parentName, testCase.getMutation(), ex.getMessage()});
            LOGGER.log(Level.FINER, "Sniper failure stacktrace", ex);
            return null;
        }

        // Sniper occasionally leaves class names stale; force the generated testcase name.
        String newClassName = testCase.getName();
        mutatedSource = mutatedSource.replace(parentName, newClassName);
        mutatedSource = mutatedSource.replace("abstractstatic", "static");

        fileManager.writeNewTestCase(testCase, mutatedSource);
        fileManager.createTestCaseDirectory(testCase);
        return testCase;
    }

    private String printWithSniper(Factory factory, CtType<?> anyTopLevelInFile) {
        var environment = factory.getEnvironment();
        var sniper = new spoon.support.sniper.SniperJavaPrettyPrinter(environment);

        var position = anyTopLevelInFile.getPosition();
        CtCompilationUnit compilationUnit =
                (position != null && position.isValidPosition() && position.getCompilationUnit() != null)
                        ? position.getCompilationUnit()
                        : factory.CompilationUnit().getOrCreate(anyTopLevelInFile);

        CtType<?>[] declaredTypes = compilationUnit.getDeclaredTypes().toArray(new CtType<?>[0]);
        return sniper.printTypes(declaredTypes);
    }
}
