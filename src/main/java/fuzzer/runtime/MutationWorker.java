package fuzzer.runtime;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.mutators.AlgebraicSimplificationEvoke;
import fuzzer.mutators.ArrayMemorySegmentShadowMutator;
import fuzzer.mutators.ArrayToMemorySegmentMutator;
import fuzzer.mutators.AutoboxEliminationEvoke;
import fuzzer.mutators.DeadCodeEliminationEvoke;
import fuzzer.mutators.DeoptimizationEvoke;
import fuzzer.mutators.EscapeAnalysisEvoke;
import fuzzer.mutators.InlineEvokeMutator;
import fuzzer.mutators.IntToLongLoopMutator;
import fuzzer.mutators.LateZeroMutator;
import fuzzer.mutators.LockCoarseningEvoke;
import fuzzer.mutators.LockEliminationEvoke;
import fuzzer.mutators.LoopPeelingEvokeMutator;
import fuzzer.mutators.LoopUnrollingEvokeMutator;
import fuzzer.mutators.LoopUnswitchingEvokeMutator;
import fuzzer.mutators.MutationContext;
import fuzzer.mutators.MutationResult;
import fuzzer.mutators.MutationStatus;
import fuzzer.mutators.Mutator;
import fuzzer.mutators.MutatorType;
import fuzzer.mutators.RedundantStoreEliminationEvoke;
import fuzzer.mutators.ReflectionCallMutator;
import fuzzer.mutators.SinkableMultiplyMutator;
import fuzzer.mutators.SplitIfStressMutator;
import fuzzer.mutators.TemplatePredicateMutator;
import fuzzer.mutators.UnswitchScaffoldMutator;
import fuzzer.runtime.scheduling.MutatorScheduler;
import fuzzer.runtime.scheduling.MutatorScheduler.MutationAttemptStatus;
import fuzzer.util.AstTreePrinter;
import fuzzer.util.FileManager;
import fuzzer.util.LoggingConfig;
import fuzzer.util.NameGenerator;
import fuzzer.util.TestCase;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.visitor.filter.TypeFilter;


public class MutationWorker implements Runnable{

    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCase> executionQueue;
    private final Random random;
    private final AstTreePrinter printer = new AstTreePrinter();
    private final boolean printAst;
    private final int minQueueCapacity;
    private final double executionQueueFraction;
    private final int maxExecutionQueueSize;
    private final FileManager fileManager;
    private final NameGenerator nameGenerator;
    private final GlobalStats globalStats;
    private final MutatorScheduler scheduler;
    private static final int HISTOGRAM_LOG_INTERVAL = 100;
    private long selectionCounter = 0L;
    private static final MutatorType[] MUTATOR_CANDIDATES = MutatorType.mutationCandidates();
    private MutationAttemptStatus lastAttemptStatus = MutationAttemptStatus.FAILED;
    private static final Map<MutatorType, Function<Random, Mutator>> MUTATOR_FACTORIES = buildFactoryMap();

    private static final Logger LOGGER = LoggingConfig.getLogger(MutationWorker.class);
    
    public MutationWorker(FileManager fm,
                          NameGenerator nameGenerator,
                          BlockingQueue<TestCase> mutationQueue,
                          BlockingQueue<TestCase> executionQueue,
                          Random random,
                          boolean printAst,
                          int minQueueCapacity,
                          double executionQueueFraction,
                          int maxExecutionQueueSize,
                          GlobalStats globalStats,
                          MutatorScheduler scheduler) {
        this.random = random;
        this.printAst = printAst;
        this.mutationQueue = mutationQueue;
        this.executionQueue = executionQueue;
        this.minQueueCapacity = minQueueCapacity;
        this.executionQueueFraction = executionQueueFraction;
        this.maxExecutionQueueSize = maxExecutionQueueSize;
        this.fileManager = fm;
        this.nameGenerator = nameGenerator;
        this.globalStats = globalStats;
        this.scheduler = scheduler;
    }

    @Override
    public void run() {
        LOGGER.info("Mutator started.");
        TestCase testCase = null;
        while (true) {
            try {
                if (!hasExecutionCapacity()) {
                    Thread.sleep(1000);
                    continue;
                }

                // take a test case from the mutation queue
                testCase = mutationQueue.take();
                
              //  for (int i = 0; i < 20; i++) {
                    // mutate the test case
                    TestCase mutatedTestCase = mutateTestCase(testCase);
                    if (mutatedTestCase != null) {
                        // add the mutated test case to the execution queue if capacity allows
                        if (!executionQueue.offer(mutatedTestCase)) {
                            LOGGER.fine(() -> String.format("Offer failed while enqueuing %s; execution queue size=%d", mutatedTestCase.getName(), executionQueue.size()));
                        }
                    } else {
                        LOGGER.fine("Skipping enqueue for null mutation result.");
                    }
               // }


                testCase.markSelected();
                logMutationSelection(testCase);
                if (testCase.isActiveChampion()) {
                    mutationQueue.put(testCase);
                }


            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.info("Mutator interrupted; shutting down.");
                return;
            } catch (Exception e) {
                String testcaseName = (testCase != null) ? testCase.getName() : "<none>";
                String mutatorName = (testCase != null && testCase.getMutation() != null)
                        ? testCase.getMutation().name()
                        : "<unknown>";
                LOGGER.severe(String.format("Mutator loop recovered from unexpected error while mutating %s using %s", testcaseName, mutatorName));
                LOGGER.severe("Mutator loop stacktrace\n" + e);
            } 
        
        }
    }

    private void logMutationSelection(TestCase testCase) {
        if (globalStats == null || testCase == null) {
            return;
        }
        globalStats.recordMutationSelection(testCase.getTimesSelected());
        selectionCounter++;
        if (selectionCounter % HISTOGRAM_LOG_INTERVAL != 0) {
            return;
        }
        long[] histogram = globalStats.snapshotMutationSelectionHistogram();
        if (histogram == null || histogram.length == 0) {
            return;
        }
        int maxBucket = histogram.length - 1;
        long total = 0L;
        int highestNonZero = 0;
        for (int i = 0; i < histogram.length; i++) {
            long count = histogram[i];
            total += count;
            if (count > 0) {
                highestNonZero = i;
            }
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Mutation selection histogram (total=").append(total)
                .append(", highestBucket=").append(highestNonZero).append("): ");
        int upperBound = Math.min(highestNonZero, 10);
        for (int i = 0; i <= upperBound; i++) {
            if (i > 0) {
                summary.append(", ");
            }
            summary.append(i).append("->").append(histogram[i]);
        }
        if (highestNonZero > upperBound) {
            summary.append(", ... , ").append(maxBucket).append("+->").append(histogram[maxBucket]);
        }
        LOGGER.info(summary.toString());
    }

    public TestCase mutateTestCaseWith(MutatorType mutatorType, TestCase parentTestCase) {

        lastAttemptStatus = MutationAttemptStatus.FAILED;

        // create a new test case and set the given test case as its parent
        String parentName = parentTestCase.getName();
        String newTestCaseName = nameGenerator.generateName();
        parentTestCase.incMutationCount();
        TestCase tc = new TestCase(newTestCaseName, parentTestCase.getOptVectors(), mutatorType, parentTestCase.getScore(), parentName, parentTestCase.getSeedName(), parentTestCase.getMutationDepth()+1, 0);
        //TestCase testCase = new TestCase(parentTestCase.getName(), parentTestCase.getPath(), parentTestCase.getOccurences());


        String sourceFilePath = fileManager.getTestCasePath(parentTestCase).toString();
    
        // Setup Spoon to parse and manipulate the Java source code
        Launcher launcher = new Launcher();
        var env = launcher.getEnvironment();
        env.setAutoImports(true);
        env.setNoClasspath(false);
        env.setCommentEnabled(true);
        env.setComplianceLevel(21);
        env.setPrettyPrinterCreator(() -> new spoon.support.sniper.SniperJavaPrettyPrinter(env));

        launcher.addInputResource(sourceFilePath);
        CtModel model = launcher.buildModel();
        Factory factory = launcher.getFactory();

        // Select and apply the mutator based on the specified type
        Mutator mutator;
        Random usedRandom = new Random(this.random.nextLong());

        mutator = createMutator(mutatorType, usedRandom);


        MutationContext ctx = new MutationContext(launcher, model, factory, this.random, parentTestCase);
        if (!mutator.isApplicable(ctx)) {
            LOGGER.log(Level.FINE,
                    String.format("Mutator %s is not applicable to parent %s",
                            mutatorType, parentTestCase.getName()));
            lastAttemptStatus = MutationAttemptStatus.NOT_APPLICABLE;
            return null;
        }

        LOGGER.log(Level.INFO, String.format("Applying mutator %s to parent testcase %s",
                mutatorType, parentTestCase.getName()));
        if (printAst) {
            printer.print(model);
        }

        try {
            MutationResult result = mutator.mutate(ctx);
            if (result == null || result.status() != MutationStatus.SUCCESS) {
                LOGGER.log(Level.INFO,
                        String.format("Mutator %s did not succeed on %s: %s",
                                mutatorType,
                                parentTestCase.getName(),
                                result != null ? result.detail() : "null result"));
                lastAttemptStatus = MutationAttemptStatus.FAILED;
                return null;
            }
            LOGGER.info(String.format("Mutator %s applied successfully with seed %d to parent %s, created testcase %s",
                    mutatorType,
                    usedRandom.nextLong(),
                    parentTestCase.getName(),
                    tc.getName()));
        } catch (Exception ex) {
            LOGGER.log(Level.INFO,
                    String.format("Mutator %s failed for parent %s: %s",
                            mutatorType, parentTestCase.getName(), ex.getMessage()));
            LOGGER.log(Level.INFO, "Mutator failure stacktrace", ex);
            lastAttemptStatus = MutationAttemptStatus.FAILED;
            return null;
        }
        
        if (printAst) {
            printer.print(model);
        }

        TestCase finalized = finalizeMutation(parentTestCase.getName(), model, launcher, tc);
        if (finalized == null) {
            LOGGER.log(Level.FINE,
                    String.format("Mutator %s produced no output for parent %s",
                            mutatorType, parentTestCase.getName()));
            lastAttemptStatus = MutationAttemptStatus.FAILED;
            return null;
        }
        lastAttemptStatus = MutationAttemptStatus.SUCCESS;
        return finalized;
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
        map.put(MutatorType.INLINE_EVOKE, InlineEvokeMutator::new);
        map.put(MutatorType.LOOP_UNROLLING_EVOKE, LoopUnrollingEvokeMutator::new);
        map.put(MutatorType.REFLECTION_CALL, ReflectionCallMutator::new);
        map.put(MutatorType.REDUNDANT_STORE_ELIMINATION_EVOKE, RedundantStoreEliminationEvoke::new);
        map.put(MutatorType.AUTOBOX_ELIMINATION_EVOKE, AutoboxEliminationEvoke::new);
        map.put(MutatorType.ESCAPE_ANALYSIS_EVOKE, EscapeAnalysisEvoke::new);
        map.put(MutatorType.LOOP_PEELING_EVOKE, LoopPeelingEvokeMutator::new);
        map.put(MutatorType.LOOP_UNSWITCHING_EVOKE, LoopUnswitchingEvokeMutator::new);
        map.put(MutatorType.DEOPTIMIZATION_EVOKE, DeoptimizationEvoke::new);
        map.put(MutatorType.LATE_ZERO_MUTATOR, LateZeroMutator::new);
        map.put(MutatorType.SPLIT_IF_STRESS, SplitIfStressMutator::new);
        map.put(MutatorType.UNSWITCH_SCAFFOLD, UnswitchScaffoldMutator::new);
        map.put(MutatorType.SINKABLE_MUL, SinkableMultiplyMutator::new);
        map.put(MutatorType.TEMPLATE_PREDICATE, TemplatePredicateMutator::new);
        map.put(MutatorType.ALGEBRAIC_SIMPLIFICATION_EVOKE, AlgebraicSimplificationEvoke::new);
        map.put(MutatorType.DEAD_CODE_ELIMINATION_EVOKE, DeadCodeEliminationEvoke::new);
        map.put(MutatorType.LOCK_ELIMINATION_EVOKE, LockEliminationEvoke::new);
        map.put(MutatorType.LOCK_COARSENING_EVOKE, LockCoarseningEvoke::new);
        map.put(MutatorType.INT_TO_LONG_LOOP, IntToLongLoopMutator::new);
        map.put(MutatorType.ARRAY_TO_MEMORY_SEGMENT, ArrayToMemorySegmentMutator::new);
        map.put(MutatorType.ARRAY_MEMORY_SEGMENT_SHADOW, ArrayMemorySegmentShadowMutator::new);
        return map;
    }

    public TestCase mutateTestCase(TestCase parentTestCase) {
        if (MUTATOR_CANDIDATES.length == 0) {
            return null;
        }
        EnumSet<MutatorType> attempted = EnumSet.noneOf(MutatorType.class);
        int maxAttempts = MUTATOR_CANDIDATES.length;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            MutatorType chosen = chooseMutator(parentTestCase, attempted);
            if (chosen == null) {
                break;
            }
            attempted.add(chosen);
            TestCase result = mutateTestCaseWith(chosen, parentTestCase);
            MutationAttemptStatus status = lastAttemptStatus;
            recordAttempt(chosen, status);
            if (status == MutationAttemptStatus.SUCCESS && result != null) {
                return result;
            }
        }
        return null;
    }

    private MutatorType chooseMutator(TestCase parentTestCase, EnumSet<MutatorType> attempted) {
        if (scheduler != null) {
            MutatorType candidate = scheduler.pickMutator(parentTestCase);
            if (!attempted.contains(candidate)) {
                return candidate;
            }
            // Fallback: pick untried mutator to avoid tight loops
            for (MutatorType fallback : MUTATOR_CANDIDATES) {
                if (!attempted.contains(fallback)) {
                    return fallback;
                }
            }
            return candidate;
        }
        MutatorType[] candidates = MUTATOR_CANDIDATES;
        return candidates[random.nextInt(candidates.length)];
    }

    private void recordAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        if (scheduler != null && mutatorType != null) {
            scheduler.recordMutationAttempt(mutatorType, status);
        }
        if (globalStats != null && mutatorType != null) {
            globalStats.recordMutatorMutationAttempt(mutatorType, status);
        }
    }

    private String printWithSniper(Factory factory, CtType<?> anyTopLevelInThatFile) {
    Environment env = factory.getEnvironment();
    spoon.support.sniper.SniperJavaPrettyPrinter sniper =
        new spoon.support.sniper.SniperJavaPrettyPrinter(env);

    // Use the ORIGINAL CU for this file (required by Sniper)
    // Prefer the CU from the position; if not present, ask the factory.
    var pos = anyTopLevelInThatFile.getPosition();
    spoon.reflect.declaration.CtCompilationUnit cu =
        (pos != null && pos.isValidPosition() && pos.getCompilationUnit() != null)
            ? pos.getCompilationUnit()
            : factory.CompilationUnit().getOrCreate(anyTopLevelInThatFile);

    // Sniper expects the CU’s declared types (not arbitrary lists, not clones)
    var types = cu.getDeclaredTypes().toArray(new CtType<?>[0]);

    return sniper.printTypes(types);
}


    public TestCase finalizeMutation(String parentName, CtModel model, Launcher launcher, TestCase tc) {

        CtType<?> top = model.getElements(new TypeFilter<CtType<?>>(CtType.class)).stream()
            .filter(CtType::isTopLevel)
            .findFirst()
            .orElseThrow();

        String mutatedSource;
        try {
            mutatedSource = printWithSniper(launcher.getFactory(), top);
        } catch (IllegalStateException ex) {
            LOGGER.log(Level.WARNING,
                    "Sniper pretty-printer failed for {0} using mutator {1}: {2}",
                    new Object[]{parentName, tc.getMutation(), ex.getMessage()});
            LOGGER.log(Level.FINER, "Sniper failure stacktrace", ex);
            return null;
        }
        // get new class name using name generator
        String newClassName = nameGenerator.generateName();
        mutatedSource = mutatedSource.replace(parentName, newClassName);
        mutatedSource = mutatedSource.replace("abstractstatic", "static");
        
        // String modifiedSource = sb.toString();
        tc.setName(newClassName);
        fileManager.writeNewTestCase(tc, mutatedSource);
        fileManager.createTestCaseDirectory(tc);

        return tc;
    }

    private boolean hasExecutionCapacity() {
        if (maxExecutionQueueSize > 0 && executionQueue.size() >= maxExecutionQueueSize) {
            return false;
        }
        int minCapacity = this.minQueueCapacity;
        double fraction = this.executionQueueFraction;
        if (minCapacity <= 0 && fraction <= 0.0) {
            return true;
        }
        int dynamicLimit = (minCapacity > 0) ? minCapacity : 0;
        if (fraction > 0.0) {
            int candidate = (int) Math.ceil(mutationQueue.size() * fraction);
            if (candidate > dynamicLimit) {
                dynamicLimit = candidate;
            }
        }
        if (dynamicLimit <= 0) {
            return true;
        }
        return executionQueue.size() < dynamicLimit;
    }
}
