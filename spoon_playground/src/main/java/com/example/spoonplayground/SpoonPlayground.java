package com.example.spoonplayground;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import spoon.Launcher;
import spoon.OutputType;
import spoon.compiler.Environment;
import spoon.reflect.CtModel;

public final class SpoonPlayground {

    public static void main(String[] args) {
        CliOptions options = CliOptions.parse(args);
        if (options == null) {
            return;
        }
        new SpoonPlayground().run(options);
    }

    private void run(CliOptions options) {
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(options.inputDir())) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        } catch (IOException ioe) {
            System.err.println("Failed to read input directory: " + ioe.getMessage());
            if (options.verbose()) {
                ioe.printStackTrace(System.err);
            }
            return;
        }

        if (javaFiles.isEmpty()) {
            System.err.println("No .java files found under " + options.inputDir());
            return;
        }

        for (Path javaFile : javaFiles) {
            processFile(javaFile, options);
        }
    }

    private void processFile(Path javaFile, CliOptions options) {
        System.out.println("\n=== " + javaFile + " ===");

        Launcher launcher = new Launcher();
        Environment env = launcher.getEnvironment();
        env.setComplianceLevel(options.complianceLevel());
        env.setPreviewFeaturesEnabled(options.previewFeatures());
        env.setNoClasspath(options.noClasspath());
        env.setIgnoreSyntaxErrors(options.ignoreSyntaxErrors());
        env.setCommentEnabled(false);
        env.setOutputType(OutputType.NO_OUTPUT);

        launcher.addInputResource(javaFile.toString());

        try {
            CtModel model = launcher.buildModel();
            new AstTreePrinter().print(model);
        } catch (Exception ex) {
            System.err.println("Failed to build model for " + javaFile + ": " + ex.getMessage());
            if (options.verbose()) {
                ex.printStackTrace(System.err);
            }
        }
    }
}
