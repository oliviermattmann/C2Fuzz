package fuzzer.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.logging.Logger;

import fuzzer.ScoringMode;
import fuzzer.mutators.MutatorType;

/**
 * Immutable configuration for a single fuzzer session. Handles CLI parsing,
 * environment/property fallbacks, and default value resolution so the runtime
 * components can rely on a single source of truth.
 */
public final class FuzzerConfig {

    public static final String DEFAULT_DEBUG_JDK_PATH =
            "/home/oli/Documents/education/eth/msc-thesis/code/jdk/build/linux-x86_64-server-fastdebug/jdk/bin";
    public static final String DEFAULT_RELEASE_JDK_PATH =
            "/home/oli/Documents/education/eth/msc-thesis/code/jdk/build/linux-x86_64-server-release/jdk/bin";
    public static final String ENV_DEBUG_JDK_PATH = "C2FUZZ_DEBUG_JDK";
    public static final String ENV_RELEASE_JDK_PATH = "C2FUZZ_RELEASE_JDK";

    public enum Mode {
        FUZZ,
        TEST_MUTATOR
    }

    private final Mode mode;
    private final MutatorType mutatorType;
    private final boolean printAst;
    private final String seedsDir;
    private final String seedpoolDir;
    private final String debugJdkPath;
    private final String releaseJdkPath;
    private final int executorThreads;
    private final Long configuredRngSeed;
    private final ScoringMode scoringMode;
    private final String timestamp;

    private FuzzerConfig(Builder builder) {
        this.mode = builder.mode;
        this.mutatorType = builder.mutatorType;
        this.printAst = builder.printAst;
        this.seedsDir = builder.seedsDir;
        this.seedpoolDir = builder.seedpoolDir;
        this.debugJdkPath = builder.debugJdkPath;
        this.releaseJdkPath = builder.releaseJdkPath;
        this.executorThreads = builder.executorThreads;
        this.configuredRngSeed = builder.rngSeed;
        this.scoringMode = builder.scoringMode;
        this.timestamp = builder.timestamp;
    }

    public Mode mode() {
        return mode;
    }

    public MutatorType mutatorType() {
        return mutatorType;
    }

    public boolean printAst() {
        return printAst;
    }

    public String seedsDir() {
        return seedsDir;
    }

    public Optional<String> seedpoolDir() {
        return Optional.ofNullable(seedpoolDir);
    }

    public String debugJdkPath() {
        return debugJdkPath;
    }

    public String releaseJdkPath() {
        return releaseJdkPath;
    }

    public int executorThreads() {
        return executorThreads;
    }

    public OptionalLong configuredRngSeed() {
        return configuredRngSeed == null
                ? OptionalLong.empty()
                : OptionalLong.of(configuredRngSeed);
    }

    public ScoringMode scoringMode() {
        return scoringMode;
    }

    public String timestamp() {
        return timestamp;
    }

    public static FuzzerConfig fromArgs(String[] args, String timestamp, Logger logger) {
        Builder builder = new Builder(timestamp, logger);
        builder.parseArgs(args);
        return builder.build();
    }

    public static Builder builder(String timestamp, Logger logger) {
        return new Builder(timestamp, logger);
    }

    public static final class Builder {
        private final Logger logger;
        private Mode mode = Mode.FUZZ;
        private MutatorType mutatorType = MutatorType.INLINE_EVOKE;
        private boolean printAst;
        private String seedsDir;
        private String seedpoolDir;
        private String debugJdkPath;
        private String releaseJdkPath;
        private int executorThreads = 4;
        private Long rngSeed;
        private ScoringMode scoringMode = ScoringMode.PF_IDF;
        private boolean scoringModeExplicit;
        private final String timestamp;

        private Builder(String timestamp, Logger logger) {
            this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
            this.logger = Objects.requireNonNull(logger, "logger");
        }

        private void parseArgs(String[] args) {
            List<String> argList = Arrays.asList(args);

            if (argList.contains("--print-ast")) {
                logger.info("AST printing enabled via command line argument.");
                printAst = true;
            }

            int idx = argList.indexOf("--debug-jdk");
            if (idx != -1 && idx + 1 < argList.size()) {
                debugJdkPath = argList.get(idx + 1);
            }

            idx = argList.indexOf("--release-jdk");
            if (idx != -1 && idx + 1 < argList.size()) {
                releaseJdkPath = argList.get(idx + 1);
            }

            idx = argList.indexOf("--scoring");
            if (idx != -1) {
                if (idx + 1 >= argList.size()) {
                    logger.warning("Flag --scoring provided without a mode; retaining default scoring mode.");
                } else {
                    String requestedMode = argList.get(idx + 1);
                    ScoringMode parsed = ScoringMode.parseOrNull(requestedMode);
                    if (parsed != null) {
                        scoringMode = parsed;
                        scoringModeExplicit = true;
                        logger.info(() -> String.format(Locale.ROOT,
                                "Scoring mode set via CLI: %s",
                                scoringMode.displayName()));
                    } else {
                        logger.warning(() -> String.format(Locale.ROOT,
                                "Unknown scoring mode '%s' specified via --scoring; retaining default %s",
                                requestedMode,
                                scoringMode.displayName()));
                    }
                }
            }

            if (!scoringModeExplicit) {
                String raw = System.getProperty("c2fuzz.scoring");
                if (raw == null || raw.isBlank()) {
                    raw = System.getenv("C2FUZZ_SCORING");
                }
                ScoringMode parsed = ScoringMode.parseOrNull(raw);
                if (parsed != null) {
                    scoringMode = parsed;
                    logger.info(String.format(Locale.ROOT,
                            "Scoring mode resolved from property/environment: %s",
                            scoringMode.displayName()));
                } else if (raw != null && !raw.isBlank()) {
                    logger.warning(String.format(Locale.ROOT,
                            "Unknown scoring mode '%s' from property/environment; using default %s",
                            raw,
                            scoringMode.displayName()));
                }
            }

            logger.info(String.format(Locale.ROOT,
                    "Using scoring mode: %s",
                    scoringMode.displayName()));

            idx = argList.indexOf("--jdk");
            if (idx != -1 && idx + 1 < argList.size()) {
                String unifiedJdk = argList.get(idx + 1);
                debugJdkPath = unifiedJdk;
                releaseJdkPath = unifiedJdk;
            }

            idx = argList.indexOf("--executors");
            if (idx != -1 && idx + 1 < argList.size()) {
                String threadArg = argList.get(idx + 1);
                try {
                    int parsed = Integer.parseInt(threadArg);
                    if (parsed <= 0) {
                        logger.warning(String.format(
                                "Ignoring non-positive executor count %d. Keeping %d threads.",
                                parsed,
                                executorThreads));
                    } else {
                        executorThreads = parsed;
                    }
                } catch (NumberFormatException nfe) {
                    logger.warning(String.format(
                            "Invalid executor count '%s'. Keeping %d threads.",
                            threadArg,
                            executorThreads));
                }
            }

            idx = argList.indexOf("--seedpool");
            if (idx != -1 && idx + 1 < argList.size()) {
                seedpoolDir = argList.get(idx + 1);
            }

            int mutatorIdx = argList.indexOf("--test-mutator");

            if (mutatorIdx != -1 && mutatorIdx + 1 < argList.size()) {
                String mutatorName = argList.get(mutatorIdx + 1);
                try {
                    mutatorType = MutatorType.valueOf(mutatorName);
                } catch (IllegalArgumentException iae) {
                    logger.warning(String.format(
                            "Unknown mutator type specified: %s. Defaulting to INLINE_EVOKE.",
                            mutatorName));
                    mutatorType = MutatorType.INLINE_EVOKE;
                }
                mode = Mode.TEST_MUTATOR;
            } else {
                mode = Mode.FUZZ;
            }

            idx = argList.indexOf("--seeds");
            if (idx != -1 && idx + 1 < argList.size()) {
                seedsDir = argList.get(idx + 1);
                logger.info(String.format("Using seeds directory: %s", seedsDir));
            } else {
                logger.warning("No seeds directory specified. Use --seeds <directory> to provide one.");
                throw new IllegalArgumentException("Seeds directory is required.");
            }

            idx = argList.indexOf("--rng");
            if (idx != -1 && idx + 1 < argList.size()) {
                String seedValue = argList.get(idx + 1);
                try {
                    rngSeed = Long.valueOf(seedValue);
                    logger.info(String.format("Using RNG seed: %d", rngSeed));
                } catch (NumberFormatException nfe) {
                    logger.warning(String.format("Invalid RNG seed provided: %s", seedValue));
                    rngSeed = null;
                }
            }
        }

        public FuzzerConfig build() {
            resolveJdkPaths();
            return new FuzzerConfig(this);
        }

        private void resolveJdkPaths() {
            if (debugJdkPath == null) {
                debugJdkPath = System.getenv(ENV_DEBUG_JDK_PATH);
            }
            if (releaseJdkPath == null) {
                releaseJdkPath = System.getenv(ENV_RELEASE_JDK_PATH);
            }
            if (debugJdkPath == null && releaseJdkPath != null) {
                debugJdkPath = releaseJdkPath;
            }
            if (releaseJdkPath == null && debugJdkPath != null) {
                releaseJdkPath = debugJdkPath;
            }

            if (debugJdkPath == null) {
                debugJdkPath = DEFAULT_DEBUG_JDK_PATH;
                logger.info(String.format("Using default debug JDK path: %s", debugJdkPath));
            } else {
                logger.info(String.format("Using debug JDK path: %s", debugJdkPath));
            }

            if (releaseJdkPath == null) {
                releaseJdkPath = DEFAULT_RELEASE_JDK_PATH;
                logger.info(String.format("Using default release JDK path: %s", releaseJdkPath));
            } else {
                logger.info(String.format("Using release JDK path: %s", releaseJdkPath));
            }

            validateJdkBinary(debugJdkPath, "java");
            validateJdkBinary(releaseJdkPath, "javac");
        }

        private void validateJdkBinary(String basePath, String binaryName) {
            if (basePath == null) {
                logger.warning(String.format("No base path provided for binary '%s'.", binaryName));
                return;
            }
            Path binaryPath = Path.of(basePath, binaryName);
            if (!Files.isExecutable(binaryPath)) {
                logger.warning(String.format("JDK binary not found or is not executable: %s", binaryPath));
            }
        }
    }
}
