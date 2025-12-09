package com.example.spoonplayground;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

record CliOptions(Path inputDir,
                  int complianceLevel,
                  boolean previewFeatures,
                  boolean noClasspath,
                  boolean ignoreSyntaxErrors,
                  boolean verbose) {

    static CliOptions parse(String[] args) {
        Path input = null;
        int compliance = 21;
        boolean preview = false;
        boolean noClasspath = true;
        boolean ignoreSyntaxErrors = true;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--input":
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for --input");
                        printUsage();
                        return null;
                    }
                    input = Path.of(args[++i]).toAbsolutePath().normalize();
                    break;
                case "--compliance":
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for --compliance");
                        printUsage();
                        return null;
                    }
                    try {
                        compliance = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException nfe) {
                        System.err.println("Compliance level must be an integer: " + nfe.getMessage());
                        printUsage();
                        return null;
                    }
                    break;
                case "--preview":
                    preview = true;
                    break;
                case "--with-classpath":
                    noClasspath = false;
                    break;
                case "--strict":
                    ignoreSyntaxErrors = false;
                    break;
                case "--verbose":
                    verbose = true;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return null;
                default:
                    System.err.println("Unknown option: " + arg);
                    printUsage();
                    return null;
            }
        }

        if (Objects.isNull(input)) {
            System.err.println("Input directory is required.");
            printUsage();
            return null;
        }
        if (!Files.isDirectory(input)) {
            System.err.println("Input path is not a directory: " + input);
            return null;
        }

        return new CliOptions(input, compliance, preview, noClasspath, ignoreSyntaxErrors, verbose);
    }

    static void printUsage() {
        System.err.println("""
                Usage: java -jar spoon-playground.jar --input <dir> [options]
                  --input <dir>       Root directory containing Java testcases (required)
                  --compliance <n>    Java version passed to Spoon (default: 21)
                  --preview           Enable preview features
                  --with-classpath    Disable Spoon's --noclasspath mode
                  --strict            Fail on syntax errors (default: ignore and continue)
                  --verbose           Print stack traces on failures
                  --help, -h          Show this help
                """);
    }
}
