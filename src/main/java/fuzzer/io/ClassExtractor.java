package fuzzer.io;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.*;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import fuzzer.logging.LoggingConfig;

/**
 * Extracts all named types (classes; optionally interfaces, enums, records)
 * from a single .java file and prvides method for  a JIT-ready -XX:CompileOnly command.
 */
public class ClassExtractor {

    private static final Logger LOGGER = LoggingConfig.getLogger(ClassExtractor.class);
    private final boolean noClasspath;
    private final int complianceLevel;

    public ClassExtractor(boolean noClasspath, int complianceLevel) {
        this.noClasspath = noClasspath;
        this.complianceLevel = complianceLevel;
    }

    public List<String> extractTypeNames(Path javaFile,
                                         boolean includeInterfaces,
                                         boolean includeEnums,
                                         boolean includeRecords) throws IOException {
        if (javaFile == null) throw new IllegalArgumentException("Path must not be null");
        Path normalized = javaFile.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Not a regular file: " + normalized);
        }
        if (!normalized.toString().endsWith(".java")) {
            throw new IllegalArgumentException("Expected a .java file: " + normalized);
        }

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(noClasspath);
        launcher.getEnvironment().setComplianceLevel(complianceLevel);
        launcher.addInputResource(normalized.toString());

        CtModel model = launcher.buildModel();
        String fileKey = canonical(normalized.toFile());

        // Get all CtTypes (classes, interfaces, enums, records, including nested & local)
        List<CtType<?>> allTypes =
                model.getElements(new TypeFilter<CtType<?>>(CtType.class));

        Class<?> ctRecordClass = tryLoadCtRecord();

        return allTypes.stream()
                // must originate from this file
                .filter(t -> t.getPosition() != null && t.getPosition().getFile() != null)
                .filter(t -> canonical(t.getPosition().getFile()).equals(fileKey))
                // skip anonymous/local with no simple name
                .filter(t -> t.getSimpleName() != null && !t.getSimpleName().isEmpty())
                // keep classes always; others based on flags
                .filter(t ->
                        t instanceof CtClass
                        || (includeInterfaces && t instanceof CtInterface)
                        || (includeEnums && t instanceof CtEnum)
                        || (includeRecords && ctRecordClass != null && ctRecordClass.isInstance(t))
                )
                .map(ClassExtractor::qualifiedNameSafe)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public static String getCompileOnlyString(List<String> typeNames) {
        List<String> patterns = (typeNames == null ? List.<String>of()
                : typeNames.stream()
                           .map(ClassExtractor::toJitClassPattern) // dots->'/' (keeps '$')
                           .map(s -> s + "::*")                    // all methods in that class
                           .toList());
    
        return "-XX:CompileOnly=" + String.join(",", patterns);
    }

    /** Prints human-readable names and a JIT-ready -XX:CompileOnly line. */
    public void printResults(Path javaFile, List<String> typeNames) {
        if (!LOGGER.isLoggable(Level.FINE)) {
            return;
        }
        Path p = javaFile.toAbsolutePath().normalize();
        LOGGER.fine("=== " + p + " ===");
        if (typeNames == null || typeNames.isEmpty()) {
            LOGGER.fine("(no named types found)");
        } else {
            typeNames.forEach(name -> LOGGER.fine(name));
        }
        LOGGER.fine("");

        List<String> jitPatterns = (typeNames == null ? List.<String>of() :
                typeNames.stream().map(ClassExtractor::toJitClassPattern).toList());

        LOGGER.fine("JIT (paste after -XX:CompileOnly=)");
        LOGGER.fine(String.join(",", jitPatterns));
        LOGGER.fine("");
    }

    // ---------- Helpers ----------

    private static String canonical(File f) {
        try { return f.getCanonicalPath(); }
        catch (IOException e) { return f.getAbsolutePath(); }
    }

    private static String qualifiedNameSafe(CtType<?> t) {
        try {
            String qn = t.getQualifiedName(); // Spoon uses $ for nested
            return (qn != null && !qn.isEmpty()) ? qn : t.getSimpleName();
        } catch (Exception e) {
            return t.getSimpleName();
        }
    }

    /** Convert package dots to '/', keep '$' for nested types. */
    private static String toJitClassPattern(String qualifiedOrBinaryName) {
        return qualifiedOrBinaryName.replace('.', '/');
    }

    /** Load CtRecord via reflection to remain compatible with older Spoon versions. */
    private static Class<?> tryLoadCtRecord() {
        try {
            return Class.forName("spoon.reflect.declaration.CtRecord");
        } catch (ClassNotFoundException e) {
            return null; // Spoon version without records support
        }
    }
}
