package fuzzer.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;

/**
 * Immutable configuration for a single fuzzer session. Handles CLI parsing,
 * environment/property fallbacks, and default value resolution so the runtime
 * components can rely on a single source of truth.
 */
public final class FuzzerConfig {

    // Default paths and values
    public static final String DEFAULT_DEBUG_JDK_PATH = "/home/oli/Documents/education/eth/msc-thesis/code/C2Fuzz/jdk/build/linux-x86_64-server-fastdebug/jdk/bin";
    public static final String ENV_DEBUG_JDK_PATH = "C2FUZZ_DEBUG_JDK";
    public static final int DEFAULT_TEST_MUTATOR_SEED_SAMPLES = 5;
    public static final int DEFAULT_TEST_MUTATOR_ITERATIONS = 3;


    // Modes of operation
    public enum Mode {
        FUZZ,
        FUZZ_ASSERTS,
        TEST_MUTATOR
    }


    // Policy for Mutator scheduling
    public enum MutatorPolicy {
        UNIFORM,
        BANDIT,
        MOP;

        public static MutatorPolicy parseOrNull(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return Arrays.stream(values())
                    .filter(p -> p.name().equalsIgnoreCase(raw))
                    .findFirst()
                    .orElse(null);
        }
        public String displayName() {
            return name().toLowerCase();
        }
    }

    // Policy for Corpus management
    public enum CorpusPolicy {
        CHAMPION,
        RANDOM;

        public static CorpusPolicy parseOrNull(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return Arrays.stream(values())
                    .filter(p -> p.name().equalsIgnoreCase(raw))
                    .findFirst()
                    .orElse(null);
        }

        public String displayName() {
            return name().toLowerCase();
        }
    }

    private final Mode mode;
    private final MutatorType mutatorType;
    private final boolean printAst;
    private final String seedsDir;
    private final String seedpoolDir;
    private final String blacklistPath;
    private final String debugJdkPath;
    private final int executorThreads;
    private final Long configuredRngSeed;
    private final ScoringMode scoringMode;
    private final Level logLevel;
    private final String timestamp;
    private final int testMutatorSeedSamples;
    private final int testMutatorIterations;
    private final MutatorPolicy mutatorPolicy;
    private final CorpusPolicy corpusPolicy;
    private final long signalIntervalSeconds;
    private final long mutatorStatsIntervalSeconds;
    private final boolean isDebug;

    private FuzzerConfig(Builder builder) {
        this.mode = builder.mode;
        this.mutatorType = builder.mutatorType;
        this.printAst = builder.printAst;
        this.seedsDir = builder.seedsDir;
        this.seedpoolDir = builder.seedpoolDir;
        this.blacklistPath = builder.blacklistPath;
        this.debugJdkPath = builder.debugJdkPath;
        this.executorThreads = builder.executorThreads;
        this.configuredRngSeed = builder.rngSeed;
        this.scoringMode = builder.scoringMode;
        this.logLevel = builder.logLevel;
        this.timestamp = builder.timestamp;
        this.testMutatorSeedSamples = builder.testMutatorSeedSamples;
        this.testMutatorIterations = builder.testMutatorIterations;
        this.mutatorPolicy = builder.mutatorPolicy;
        this.corpusPolicy = builder.corpusPolicy;
        this.signalIntervalSeconds = builder.signalIntervalSeconds;
        this.mutatorStatsIntervalSeconds = builder.mutatorStatsIntervalSeconds;
        this.isDebug = builder.isDebug;
    }

    public Mode mode() {
        return mode;
    }

    public boolean isDebug() {
        return isDebug;
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

    public Optional<String> blacklistPath() {
        return Optional.ofNullable(blacklistPath);
    }

    public String debugJdkPath() {
        return debugJdkPath;
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

    public MutatorPolicy mutatorPolicy() {
        return mutatorPolicy;
    }

    public CorpusPolicy corpusPolicy() {
        return corpusPolicy;
    }

    public long signalIntervalSeconds() {
        return signalIntervalSeconds;
    }

    public long mutatorStatsIntervalSeconds() {
        return mutatorStatsIntervalSeconds;
    }

    public Level logLevel() {
        return logLevel;
    }

    public String timestamp() {
        return timestamp;
    }

    public int testMutatorSeedSamples() {
        return testMutatorSeedSamples;
    }

    public int testMutatorIterations() {
        return testMutatorIterations;
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
        private String blacklistPath;
        private String debugJdkPath;
        private int executorThreads = 4;
        private Long rngSeed;
        private ScoringMode scoringMode = ScoringMode.PF_IDF;
        private boolean scoringModeExplicit;
        private Level logLevel = Level.INFO;
        private boolean logLevelExplicit;
        private final String timestamp;
        private int testMutatorSeedSamples = DEFAULT_TEST_MUTATOR_SEED_SAMPLES;
        private int testMutatorIterations = DEFAULT_TEST_MUTATOR_ITERATIONS;
        private MutatorPolicy mutatorPolicy = MutatorPolicy.UNIFORM;
        private boolean mutatorPolicyExplicit;
        private CorpusPolicy corpusPolicy = CorpusPolicy.CHAMPION;
        private boolean corpusPolicyExplicit;
        private long signalIntervalSeconds = java.time.Duration.ofMinutes(5).getSeconds();
        private long mutatorStatsIntervalSeconds = java.time.Duration.ofMinutes(5).getSeconds();
        private boolean isDebug;

        private Builder(String timestamp, Logger logger) {
            this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
            this.logger = Objects.requireNonNull(logger, "logger");
        }

        private void parseArgs(String[] args) {
            List<String> argList = Arrays.asList(args);

            if (argList.contains("--debug")) {
                logger.info("Debug mode enabled via command line argument.");
                isDebug = true;
            } else {
                isDebug = false;
            }

            if (argList.contains("--print-ast")) {
                logger.info("AST printing enabled via command line argument.");
                printAst = true;
            }

            int idx = argList.indexOf("--debug-jdk");
            if (idx != -1 && idx + 1 < argList.size()) {
                debugJdkPath = argList.get(idx + 1);
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
                        logger.info(String.format(
                                "Scoring mode set via CLI: %s",
                                scoringMode.displayName()));
                    } else {
                        logger.warning(String.format(
                                "Unknown scoring mode '%s' specified via --scoring; retaining default %s",
                                requestedMode,
                                scoringMode.displayName()));
                    }
                }
            }

            idx = argList.indexOf("--mutator-policy");
            if (idx != -1) {
                if (idx + 1 >= argList.size()) {
                    logger.warning("Flag --mutator-policy provided without a value; retaining default policy.");
                } else {
                    String requestedPolicy = argList.get(idx + 1);
                    MutatorPolicy parsed = MutatorPolicy.parseOrNull(requestedPolicy);
                    if (parsed != null) {
                        mutatorPolicy = parsed;
                        mutatorPolicyExplicit = true;
                        logger.info(String.format(
                                "Mutator policy set via CLI: %s",
                                mutatorPolicy.displayName()));
                    } else {
                        logger.warning(String.format(
                                "Unknown mutator policy '%s' specified via --mutator-policy; retaining default %s",
                                requestedPolicy,
                                mutatorPolicy.displayName()));
                    }
                }
            }

            idx = argList.indexOf("--corpus-policy");
            if (idx != -1) {
                if (idx + 1 >= argList.size()) {
                    logger.warning("Flag --corpus-policy provided without a value; retaining default corpus policy.");
                } else {
                    String requestedPolicy = argList.get(idx + 1);
                    CorpusPolicy parsed = CorpusPolicy.parseOrNull(requestedPolicy);
                    if (parsed != null) {
                        corpusPolicy = parsed;
                        corpusPolicyExplicit = true;
                        logger.info(String.format(
                                "Corpus policy set via CLI: %s",
                                corpusPolicy.displayName()));
                    } else {
                        logger.warning(String.format(
                                "Unknown corpus policy '%s' specified via --corpus-policy; retaining default %s",
                                requestedPolicy,
                                corpusPolicy.displayName()));
                    }
                }
            }

            idx = argList.indexOf("--signal-interval");
            if (idx != -1) {
                if (idx + 1 >= argList.size()) {
                    logger.warning("Flag --signal-interval provided without a value; retaining default interval.");
                } else {
                    String rawSeconds = argList.get(idx + 1);
                    try {
                        long parsed = Long.parseLong(rawSeconds);
                        if (parsed > 0) {
                            signalIntervalSeconds = parsed;
                            logger.info(String.format("Signal log interval set via CLI: %d seconds", signalIntervalSeconds));
                        } else {
                            logger.warning("Signal interval must be positive; retaining default interval.");
                        }
                    } catch (NumberFormatException nfe) {
                        logger.warning(String.format("Invalid signal interval '%s'; retaining default interval.", rawSeconds));
                    }
                }
            }

            idx = argList.indexOf("--mutator-interval");
            if (idx != -1) {
                if (idx + 1 >= argList.size()) {
                    logger.warning("Flag --mutator-interval provided without a value; retaining default interval.");
                } else {
                    String rawSeconds = argList.get(idx + 1);
                    try {
                        long parsed = Long.parseLong(rawSeconds);
                        if (parsed > 0) {
                            mutatorStatsIntervalSeconds = parsed;
                            logger.info(String.format("Mutator stats interval set via CLI: %d seconds", mutatorStatsIntervalSeconds));
                        } else {
                            logger.warning("Mutator stats interval must be positive; retaining default interval.");
                        }
                    } catch (NumberFormatException nfe) {
                        logger.warning(String.format("Invalid mutator stats interval '%s'; retaining default interval.", rawSeconds));
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
                    logger.info(String.format(
                            "Scoring mode resolved from property/environment: %s",
                            scoringMode.displayName()));
                } else if (raw != null && !raw.isBlank()) {
                    logger.warning(String.format(
                            "Unknown scoring mode '%s' from property/environment; using default %s",
                            raw,
                            scoringMode.displayName()));
                }
            }

            if (!mutatorPolicyExplicit) {
                String raw = System.getProperty("c2fuzz.mutatorPolicy");
                if (raw == null || raw.isBlank()) {
                    raw = System.getenv("C2FUZZ_MUTATOR_POLICY");
                }
                MutatorPolicy parsed = MutatorPolicy.parseOrNull(raw);
                if (parsed != null) {
                    mutatorPolicy = parsed;
                    logger.info(String.format(
                            "Mutator policy resolved from property/environment: %s",
                            mutatorPolicy.displayName()));
                } else if (raw != null && !raw.isBlank()) {
                    logger.warning(String.format(
                            "Unknown mutator policy '%s' from property/environment; using default %s",
                            raw,
                            mutatorPolicy.displayName()));
                }
            }

            if (!corpusPolicyExplicit) {
                String raw = System.getProperty("c2fuzz.corpusPolicy");
                if (raw == null || raw.isBlank()) {
                    raw = System.getenv("C2FUZZ_CORPUS_POLICY");
                }
                CorpusPolicy parsed = CorpusPolicy.parseOrNull(raw);
                if (parsed != null) {
                    corpusPolicy = parsed;
                    logger.info(String.format(
                            "Corpus policy resolved from property/environment: %s",
                            corpusPolicy.displayName()));
                } else if (raw != null && !raw.isBlank()) {
                    logger.warning(String.format(
                            "Unknown corpus policy '%s' from property/environment; using default %s",
                            raw,
                            corpusPolicy.displayName()));
                }
            }

            logger.info(String.format(
                    "Using scoring mode: %s",
                    scoringMode.displayName()));

            idx = argList.indexOf("--log-level");
            if (idx != -1) {
                if (idx + 1 >= argList.size()) {
                    logger.warning("Flag --log-level provided without a value; retaining default log level.");
                } else {
                    String requestedLevel = argList.get(idx + 1);
                    Level parsedLevel = parseLogLevel(requestedLevel);
                    if (parsedLevel != null) {
                        logLevel = parsedLevel;
                        logLevelExplicit = true;
                        logger.info(String.format(
                                "Log level set via CLI: %s",
                                logLevel.getName()));
                    } else {
                        logger.warning(String.format(
                                "Unknown log level '%s' specified via --log-level; retaining default %s",
                                requestedLevel,
                                logLevel.getName()));
                    }
                }
            }

            if (!logLevelExplicit) {
                String rawLevel = System.getProperty("c2fuzz.logLevel");
                if (rawLevel == null || rawLevel.isBlank()) {
                    rawLevel = System.getenv("C2FUZZ_LOG_LEVEL");
                }
                Level parsedLevel = parseLogLevel(rawLevel);
                if (parsedLevel != null) {
                    logLevel = parsedLevel;
                    logger.info(String.format(
                            "Log level resolved from property/environment: %s",
                            logLevel.getName()));
                } else if (rawLevel != null && !rawLevel.isBlank()) {
                    logger.warning(String.format(
                            "Unknown log level '%s' from property/environment; using default %s",
                            rawLevel,
                            logLevel.getName()));
                }
            }

            logger.info(String.format(
                    "Using log level: %s",
                    logLevel.getName()));

            idx = argList.indexOf("--jdk");
            if (idx != -1 && idx + 1 < argList.size()) {
                String unifiedJdk = argList.get(idx + 1);
                debugJdkPath = unifiedJdk;
            }

            idx = argList.indexOf("--mode");
            if (idx != -1 && idx + 1 < argList.size()) {
                String modeArg = argList.get(idx + 1);

                if ("test-mutator".equalsIgnoreCase(modeArg)) {
                    mode = Mode.TEST_MUTATOR;
                } else if ("fuzz-asserts".equalsIgnoreCase(modeArg)) {
                    mode = Mode.FUZZ_ASSERTS;
                } else if ("fuzz".equalsIgnoreCase(modeArg)) {
                    mode = Mode.FUZZ;
                } else {
                    logger.warning(String.format(
                            "Unknown mode '%s' specified via --mode; defaulting to FUZZ.",
                            modeArg));
                    mode = Mode.FUZZ;
                }
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

            idx = argList.indexOf("--blacklist");
            if (idx != -1 && idx + 1 < argList.size()) {
                blacklistPath = argList.get(idx + 1);
            }

            int mutatorIdx = argList.indexOf("--mutator");

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
            } 

            idx = argList.indexOf("--test-mutator-seeds");
            if (idx != -1) {
                if (idx + 1 >= argList.size()) {
                    logger.warning("Flag --test-mutator-seeds provided without a value; keeping default sample size.");
                } else {
                    String value = argList.get(idx + 1);
                    try {
                        int parsed = Integer.parseInt(value);
                        if (parsed <= 0) {
                            logger.warning(String.format(
                                    "Ignoring non-positive value %d for --test-mutator-seeds; keeping %d.",
                                    parsed,
                                    testMutatorSeedSamples));
                        } else {
                            testMutatorSeedSamples = parsed;
                            logger.info(String.format(
                                    "Test mutator seed sample size set to %d.",
                                    testMutatorSeedSamples));
                        }
                    } catch (NumberFormatException nfe) {
                        logger.warning(String.format(
                                "Invalid integer '%s' for --test-mutator-seeds; keeping %d.",
                                value,
                                testMutatorSeedSamples));
                    }
                }
            }

            idx = argList.indexOf("--test-mutator-iterations");
            if (idx != -1) {
                if (idx + 1 >= argList.size()) {
                    logger.warning("Flag --test-mutator-iterations provided without a value; keeping default iteration count.");
                } else {
                    String value = argList.get(idx + 1);
                    try {
                        int parsed = Integer.parseInt(value);
                        if (parsed <= 0) {
                            logger.warning(String.format(
                                    "Ignoring non-positive value %d for --test-mutator-iterations; keeping %d.",
                                    parsed,
                                    testMutatorIterations));
                        } else {
                            testMutatorIterations = parsed;
                            logger.info(String.format(
                                    "Test mutator iterations per seed set to %d.",
                                    testMutatorIterations));
                        }
                    } catch (NumberFormatException nfe) {
                        logger.warning(String.format(
                                "Invalid integer '%s' for --test-mutator-iterations; keeping %d.",
                                value,
                                testMutatorIterations));
                    }
                }
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

        private Level parseLogLevel(String raw) {
            if (raw == null) {
                return null;
            }
            String normalized = raw.trim();
            if (normalized.isEmpty()) {
                return null;
            }
            String upper = normalized.toUpperCase();
            try {
                if ("DEBUG".equals(upper)) {
                    return Level.FINE;
                }
                if ("TRACE".equals(upper)) {
                    return Level.FINER;
                }
                if ("WARN".equals(upper)) {
                    return Level.WARNING;
                }
                if ("ERROR".equals(upper)) {
                    return Level.SEVERE;
                }
                return Level.parse(upper);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private void resolveJdkPaths() {
            if (debugJdkPath == null) {
                debugJdkPath = System.getenv(ENV_DEBUG_JDK_PATH);
            }

            if (debugJdkPath == null) {
                debugJdkPath = DEFAULT_DEBUG_JDK_PATH;
                logger.info(String.format("Using default debug JDK path: %s", debugJdkPath));
            } else {
                logger.info(String.format("Using debug JDK path: %s", debugJdkPath));
            }

            validateJdkBinary(debugJdkPath, "java");
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
