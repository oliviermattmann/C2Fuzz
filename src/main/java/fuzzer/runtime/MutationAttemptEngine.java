package fuzzer.runtime;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.analysis.AstTreePrinter;
import fuzzer.io.FileManager;
import fuzzer.io.NameGenerator;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.TestCase;
import fuzzer.mutators.AlgebraicSimplificationMutator;
import fuzzer.mutators.ArrayMemorySegmentShadowMutator;
import fuzzer.mutators.ArrayToMemorySegmentMutator;
import fuzzer.mutators.AutoboxEliminationMutator;
import fuzzer.mutators.DeadCodeEliminationMutator;
import fuzzer.mutators.DeoptimizationMutator;
import fuzzer.mutators.EscapeAnalysisMutator;
import fuzzer.mutators.InlineMutator;
import fuzzer.mutators.IntToLongLoopMutator;
import fuzzer.mutators.LateZeroMutator;
import fuzzer.mutators.LockCoarseningMutator;
import fuzzer.mutators.LockEliminationMutator;
import fuzzer.mutators.LoopPeelingMutator;
import fuzzer.mutators.LoopUnrollingMutator;
import fuzzer.mutators.LoopUnswitchingMutator;
import fuzzer.mutators.MutationContext;
import fuzzer.mutators.MutationResult;
import fuzzer.mutators.MutationStatus;
import fuzzer.mutators.Mutator;
import fuzzer.mutators.MutatorType;
import fuzzer.mutators.RedundantStoreEliminationMutator;
import fuzzer.mutators.SinkableMultiplyMutator;
import fuzzer.mutators.SplitIfStressMutator;
import fuzzer.mutators.TemplatePredicateMutator;
import fuzzer.mutators.UnswitchScaffoldMutator;
import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.runtime.scheduling.MutatorScheduler;
import fuzzer.runtime.scheduling.MutatorScheduler.MutationAttemptStatus;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.factory.Factory;

final class MutationAttemptEngine {

    record MutationAttempt(TestCase testCase, MutationAttemptStatus status, boolean allNotApplicable) {
        static MutationAttempt success(TestCase testCase) {
            return new MutationAttempt(testCase, MutationAttemptStatus.SUCCESS, false);
        }

        static MutationAttempt notApplicable() {
            return new MutationAttempt(null, MutationAttemptStatus.NOT_APPLICABLE, false);
        }

        static MutationAttempt allMutatorsNotApplicable() {
            return new MutationAttempt(null, MutationAttemptStatus.NOT_APPLICABLE, true);
        }

        static MutationAttempt failed() {
            return new MutationAttempt(null, MutationAttemptStatus.FAILED, false);
        }
    }

    private static final Logger LOGGER = LoggingConfig.getLogger(MutationAttemptEngine.class);
    private static final MutatorType[] MUTATOR_CANDIDATES = MutatorType.mutationCandidates();
    private static final Map<MutatorType, Function<Random, Mutator>> MUTATOR_FACTORIES = buildFactoryMap();

    private final FileManager fileManager;
    private final NameGenerator nameGenerator;
    private final Random random;
    private final boolean printAst;
    private final AstTreePrinter printer = new AstTreePrinter();
    private final GlobalStats globalStats;
    private final MutatorScheduler scheduler;
    private final MutationOutputWriter outputWriter;

    MutationAttemptEngine(
            FileManager fileManager,
            NameGenerator nameGenerator,
            Random random,
            boolean printAst,
            GlobalStats globalStats,
            MutatorScheduler scheduler) {
        this.fileManager = Objects.requireNonNull(fileManager, "fileManager");
        this.nameGenerator = Objects.requireNonNull(nameGenerator, "nameGenerator");
        this.random = Objects.requireNonNull(random, "random");
        this.printAst = printAst;
        this.globalStats = Objects.requireNonNull(globalStats, "globalStats");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.outputWriter = new MutationOutputWriter(fileManager);
    }

    MutationAttempt mutate(TestCase parentTestCase) {
        if (MUTATOR_CANDIDATES.length == 0) {
            return MutationAttempt.failed();
        }

        EnumSet<MutatorType> attempted = EnumSet.noneOf(MutatorType.class);
        boolean anyApplicable = false;
        int maxAttempts = MUTATOR_CANDIDATES.length;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            MutatorType mutatorType = chooseMutator(parentTestCase, attempted);
            if (mutatorType == null) {
                break;
            }
            attempted.add(mutatorType);

            MutationAttempt mutationAttempt = mutateWith(mutatorType, parentTestCase);
            recordAttempt(mutatorType, mutationAttempt.status());

            if (mutationAttempt.status() != MutationAttemptStatus.NOT_APPLICABLE) {
                anyApplicable = true;
            }
            if (mutationAttempt.status() == MutationAttemptStatus.SUCCESS
                    && mutationAttempt.testCase() != null) {
                return mutationAttempt;
            }
        }

        if (!anyApplicable) {
            LOGGER.info(String.format(
                    "All mutators not applicable for parent %s; skipping mutation.",
                    parentTestCase.getName()));
            return MutationAttempt.allMutatorsNotApplicable();
        }
        return MutationAttempt.failed();
    }

    private MutationAttempt mutateWith(MutatorType mutatorType, TestCase parentTestCase) {
        String parentName = parentTestCase.getName();
        String newTestCaseName = nameGenerator.generateName();
        parentTestCase.incMutationCount();
        TestCase testCase = new TestCase(
                newTestCaseName,
                parentTestCase.getOptVectors(),
                mutatorType,
                parentTestCase.getScore(),
                parentName,
                parentTestCase.getSeedName(),
                parentTestCase.getMutationDepth() + 1,
                0);

        long startNanos = System.nanoTime();
        String sourceFilePath = fileManager.getTestCasePath(parentTestCase).toString();

        Launcher launcher = new Launcher();
        var environment = launcher.getEnvironment();
        environment.setAutoImports(true);
        environment.setNoClasspath(false);
        environment.setCommentEnabled(true);
        environment.setComplianceLevel(21);
        environment.setPrettyPrinterCreator(() -> new spoon.support.sniper.SniperJavaPrettyPrinter(environment));

        launcher.addInputResource(sourceFilePath);
        CtModel model = launcher.buildModel();
        Factory factory = launcher.getFactory();

        long mutationSeed = random.nextLong();
        Random usedRandom = new Random(mutationSeed);
        Mutator mutator = createMutator(mutatorType, usedRandom);

        MutationContext ctx = new MutationContext(launcher, model, factory, parentTestCase);
        if (!mutator.isApplicable(ctx)) {
            LOGGER.log(Level.FINE,
                    String.format("Mutator %s is not applicable to parent %s",
                            mutatorType, parentTestCase.getName()));
            return MutationAttempt.notApplicable();
        }

        LOGGER.log(Level.FINE, String.format("Applying mutator %s to parent testcase %s",
                mutatorType, parentTestCase.getName()));
        if (printAst) {
            printer.print(model);
        }

        try {
            MutationResult result = mutator.mutate(ctx);
            if (result == null || result.status() != MutationStatus.SUCCESS) {
                LOGGER.log(Level.FINE,
                        String.format("Mutator %s did not succeed on %s: %s",
                                mutatorType,
                                parentTestCase.getName(),
                                result != null ? result.detail() : "null result"));
                return MutationAttempt.failed();
            }

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            LOGGER.info(String.format(
                    "Mutator %s applied successfully with seed %d to parent %s, created testcase %s (duration=%d ms)",
                    mutatorType,
                    mutationSeed,
                    parentTestCase.getName(),
                    testCase.getName(),
                    elapsedMs));
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING,
                    String.format("Mutator %s failed for parent %s: %s",
                            mutatorType, parentTestCase.getName(), ex.getMessage()));
            LOGGER.log(Level.FINE, "Mutator failure stacktrace", ex);
            return MutationAttempt.failed();
        }

        if (printAst) {
            printer.print(model);
        }

        TestCase finalized = outputWriter.finalizeMutation(parentName, model, launcher, testCase);
        if (finalized == null) {
            LOGGER.log(Level.FINE,
                    String.format("Mutator %s produced no output for parent %s",
                            mutatorType, parentTestCase.getName()));
            return MutationAttempt.failed();
        }
        return MutationAttempt.success(finalized);
    }

    private MutatorType chooseMutator(TestCase parentTestCase, EnumSet<MutatorType> attempted) {
        MutatorType candidate = scheduler.pickMutator(parentTestCase);
        if (candidate != null && !attempted.contains(candidate)) {
            return candidate;
        }

        int remaining = MUTATOR_CANDIDATES.length - attempted.size();
        if (remaining <= 0) {
            return candidate;
        }

        MutatorType[] pool = new MutatorType[remaining];
        int index = 0;
        for (MutatorType fallback : MUTATOR_CANDIDATES) {
            if (!attempted.contains(fallback)) {
                pool[index++] = fallback;
            }
        }
        return pool[random.nextInt(pool.length)];
    }

    private void recordAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        globalStats.recordMutatorMutationAttempt(mutatorType, status);
    }

    private static Mutator createMutator(MutatorType type, Random random) {
        Function<Random, Mutator> factory = MUTATOR_FACTORIES.get(type);
        if (factory == null) {
            throw new IllegalStateException("Unexpected mutator type: " + type);
        }
        return factory.apply(random);
    }

    private static Map<MutatorType, Function<Random, Mutator>> buildFactoryMap() {
        Map<MutatorType, Function<Random, Mutator>> map = new EnumMap<>(MutatorType.class);
        map.put(MutatorType.INLINE, InlineMutator::new);
        map.put(MutatorType.LOOP_UNROLLING, LoopUnrollingMutator::new);
        map.put(MutatorType.REDUNDANT_STORE_ELIMINATION, RedundantStoreEliminationMutator::new);
        map.put(MutatorType.AUTOBOX_ELIMINATION, AutoboxEliminationMutator::new);
        map.put(MutatorType.ESCAPE_ANALYSIS, EscapeAnalysisMutator::new);
        map.put(MutatorType.LOOP_PEELING, LoopPeelingMutator::new);
        map.put(MutatorType.LOOP_UNSWITCHING, LoopUnswitchingMutator::new);
        map.put(MutatorType.DEOPTIMIZATION, DeoptimizationMutator::new);
        map.put(MutatorType.LATE_ZERO, LateZeroMutator::new);
        map.put(MutatorType.SPLIT_IF_STRESS, SplitIfStressMutator::new);
        map.put(MutatorType.UNSWITCH_SCAFFOLD, UnswitchScaffoldMutator::new);
        map.put(MutatorType.SINKABLE_MULTIPLY, SinkableMultiplyMutator::new);
        map.put(MutatorType.TEMPLATE_PREDICATE, TemplatePredicateMutator::new);
        map.put(MutatorType.ALGEBRAIC_SIMPLIFICATION, AlgebraicSimplificationMutator::new);
        map.put(MutatorType.DEAD_CODE_ELIMINATION, DeadCodeEliminationMutator::new);
        map.put(MutatorType.LOCK_ELIMINATION, LockEliminationMutator::new);
        map.put(MutatorType.LOCK_COARSENING, LockCoarseningMutator::new);
        map.put(MutatorType.INT_TO_LONG_LOOP, IntToLongLoopMutator::new);
        map.put(MutatorType.ARRAY_TO_MEMORY_SEGMENT, ArrayToMemorySegmentMutator::new);
        map.put(MutatorType.ARRAY_MEMORY_SEGMENT_SHADOW, ArrayMemorySegmentShadowMutator::new);
        return map;
    }
}
