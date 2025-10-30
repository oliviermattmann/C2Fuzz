package fuzzer.runtime;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.mutators.AlgebraicSimplificationEvoke;
import fuzzer.mutators.AutoboxEliminationEvoke;
import fuzzer.mutators.DeadCodeEliminationEvoke;
import fuzzer.mutators.DeoptimizationEvoke;
import fuzzer.mutators.EscapeAnalysisEvoke;
import fuzzer.mutators.InlineEvokeMutator;
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
    private static final int HISTOGRAM_LOG_INTERVAL = 100;
    private long selectionCounter = 0L;
    private static final MutatorType[] MUTATOR_CANDIDATES = MutatorType.mutationCandidates();

    private static final Logger LOGGER = LoggingConfig.getLogger(MutationWorker.class);
    
    public MutationWorker(FileManager fm, NameGenerator nameGenerator, BlockingQueue<TestCase> mutationQueue, BlockingQueue<TestCase> executionQueue, Random random, boolean printAst, int minQueueCapacity, double executionQueueFraction, int maxExecutionQueueSize, GlobalStats globalStats) {
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
                    TestCase mutatedTestCase = mutateTestCaseRandom(testCase);
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

        // create a new test case and set the given test case as its parent
        String parentName = parentTestCase.getName();
        String newTestCaseName = nameGenerator.generateName();
        TestCase tc = new TestCase(newTestCaseName, parentTestCase.getOptVectors(), mutatorType, parentTestCase.getScore(), parentName);
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
        switch (mutatorType) {
            case INLINE_EVOKE -> {
                mutator = new InlineEvokeMutator(this.random);
                break;
            }
            case LOOP_UNROLLING_EVOKE -> {
                mutator = new LoopUnrollingEvokeMutator(this.random);
                break;
            }
            case REFLECTION_CALL -> {
                mutator = new ReflectionCallMutator(this.random);
                break;
            }
            case REDUNDANT_STORE_ELIMINATION_EVOKE -> {
                mutator = new RedundantStoreEliminationEvoke(this.random);
                break;
            }
            case AUTOBOX_ELIMINATION_EVOKE -> {
                mutator = new AutoboxEliminationEvoke(this.random);
                break;
            }
            case ESCAPE_ANALYSIS_EVOKE -> {
                mutator = new EscapeAnalysisEvoke(this.random);
                break;
            }
            case LOOP_PEELING_EVOKE -> {
                mutator = new LoopPeelingEvokeMutator(this.random);
                break;
            }
            case LOOP_UNSWITCHING_EVOKE -> {
                mutator = new LoopUnswitchingEvokeMutator(this.random);
                break;
            }   
            case DEOPTIMIZATION_EVOKE -> {
                mutator = new DeoptimizationEvoke(this.random);
                break;
            }
            case ALGEBRAIC_SIMPLIFICATION_EVOKE -> {
                mutator = new AlgebraicSimplificationEvoke(this.random);
                break;
            }
            case DEAD_CODE_ELIMINATION_EVOKE -> {
                mutator = new DeadCodeEliminationEvoke(this.random);
                break;
            }
            case LOCK_ELIMINATION_EVOKE -> {
                mutator = new LockEliminationEvoke(this.random);
                break;
            }
            case LOCK_COARSENING_EVOKE -> {
                mutator = new LockCoarseningEvoke(this.random);
                break;
            }   
            default -> {
                throw new IllegalStateException("Unexpected mutator type: " + mutatorType);
            }
        }

        MutationContext ctx = new MutationContext(launcher, model, factory, this.random, parentTestCase);

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
                return null;
            }
        } catch (Exception ex) {
            LOGGER.log(Level.INFO,
                    String.format("Mutator %s failed for parent %s: %s",
                            mutatorType, parentTestCase.getName(), ex.getMessage()));
            LOGGER.log(Level.INFO, "Mutator failure stacktrace", ex);
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
        }
        return finalized;
    }

    public TestCase mutateTestCaseRandom(TestCase parentTestCase) {
        // randomly select a mutator type
        MutatorType selected = selectMutatorType();
        return mutateTestCaseWith(selected, parentTestCase);
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
        
        // String modifiedSource = sb.toString();
        tc.setName(newClassName);
        fileManager.writeNewTestCase(tc, mutatedSource);
        fileManager.createTestCaseDirectory(tc);

        return tc;
    }

    private MutatorType selectMutatorType() {
        MutatorType[] candidates = MUTATOR_CANDIDATES;
        return candidates[random.nextInt(candidates.length)];
        // if (candidates.length == 0) {
        //     return MutatorType.SEED;
        // }
        // double[] weights = (globalStats != null) ? globalStats.getMutatorWeights(candidates) : null;
        // if (weights == null || weights.length != candidates.length) {
        //     return candidates[random.nextInt(candidates.length)];
        // }
        // double total = 0.0;
        // for (double weight : weights) {
        //     if (Double.isFinite(weight) && weight > 0.0) {
        //         total += weight;
        //     }
        // }
        // if (!(total > 0.0)) {
        //     return candidates[random.nextInt(candidates.length)];
        // }
        // double r = random.nextDouble() * total;
        // for (int i = 0; i < candidates.length; i++) {
        //     double weight = weights[i];
        //     if (!Double.isFinite(weight) || weight <= 0.0) {
        //         continue;
        //     }
        //     r -= weight;
        //     if (r <= 0.0) {
        //         return candidates[i];
        //     }
        // }
        // return candidates[candidates.length - 1];
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
