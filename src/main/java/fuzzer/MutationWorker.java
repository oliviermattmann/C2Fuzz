package fuzzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
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
import fuzzer.mutators.Mutator;
import fuzzer.mutators.MutatorType;
import fuzzer.mutators.RedundantStoreEliminationEvoke;
import fuzzer.mutators.ReflectionCallMutator;
import fuzzer.util.AstTreePrinter;
import fuzzer.util.LoggingConfig;
import fuzzer.util.TestCase;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.filter.TypeFilter;


public class MutationWorker implements Runnable{

    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCase> executionQueue;
    private final Random random;
    private final AstTreePrinter printer = new AstTreePrinter();
    private final boolean printAst;
    private final String seedpoolDir;
    private final int maxQueueSize;

    private static final Logger LOGGER = LoggingConfig.getLogger(MutationWorker.class);
    
    public MutationWorker(BlockingQueue<TestCase> mutationQueue, BlockingQueue<TestCase> executionQueue, Random random, boolean printAst, String seedpoolDir, int maxQueueSize) {
        this.random = random;
        this.printAst = printAst;
        this.seedpoolDir = seedpoolDir;
        this.mutationQueue = mutationQueue;
        this.executionQueue = executionQueue;
        this.maxQueueSize = maxQueueSize;
    }

    @Override
    public void run() {
        LOGGER.info("Evaluator started.");
        while (true) {
            try {

                // do very simple culling of mutation queue for now (remove 20 test which score with lowest score)
                if (mutationQueue.size() > maxQueueSize) {
                    // cull the queue
                    int toCull = mutationQueue.size()  - maxQueueSize;
                    for (int i = 0; i < toCull; i++) {
                        TestCase worst = Collections.max(mutationQueue);
                        if (worst == null) break;
                        mutationQueue.remove(worst);
                    }
                }

                // take a test case from the mutation queue
                TestCase testCase = mutationQueue.take();
                LOGGER.info("Mutating test case: " + testCase.getName() + ", Path: " + testCase.getPath() + ", Parent: " + testCase.getParentName() + ", Parent Path: " + testCase.getParentPath() + ", Target Mutation: " + testCase.getMutation());
                
                // mutate the test case

                TestCase mutatedTestCase = mutateTestCaseRandom(testCase);

                // add the mutated test case to the execution queue
                executionQueue.put(mutatedTestCase);

                testCase.markSelected();
                mutationQueue.put(testCase);


                // wait until the execution queue has less than 25 test cases
                while (executionQueue.size() > 25) {
                    Thread.sleep(100);
                }

                // then we continue with the next test case


            } catch (Exception e) {
            } 
        
        }
    }

    public TestCase mutateTestCaseWith(MutatorType mutatorType, TestCase parentTestCase) {

        // create a new test case and set the given test case as its parent
        TestCase testCase = new TestCase(parentTestCase.getName(), parentTestCase.getPath(), parentTestCase.getOccurences());

        testCase.setMutation(mutatorType);

        // mutate the test case and add it to the queue
        String sourceFilePath = parentTestCase.getPath();
        

        // Setup Spoon to parse and manipulate the Java source code
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourceFilePath);
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setNoClasspath(false);


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

        if (printAst) {
            //System.out.println("Original AST:");
            printer.print(model);
        }

        mutator.mutate(launcher, model, factory);
        
        if (printAst) {
            //System.out.println("Mutant AST:");
            printer.print(model);
        }

        finalizeMutation(parentTestCase, model, factory, testCase);

        LOGGER.info(String.format("Mutated test case created: %s, Path: %s, Parent: %s, Parent Path: %s, Applied Mutation: %s", testCase.getName(), testCase.getPath(), testCase.getParentName(), testCase.getParentPath(), testCase.getMutation()));


        return testCase;
    }

    public TestCase mutateTestCaseRandom(TestCase parentTestCase) {
        // randomly select a mutator type
        return mutateTestCaseWith(MutatorType.getRandomMutatorType(random), parentTestCase);
    }

    public TestCase finalizeMutation(TestCase parentTestCase, CtModel model, Factory factory, TestCase tc) {
        String publicClassName = parentTestCase.getName();
        if (publicClassName == null || publicClassName.isBlank()) {
            throw new IllegalStateException("Parent test case has no name; cannot derive child class name.");
        }

        String newClassName = allocateDeterministicName(parentTestCase, tc);
    
        CtClass<?> mainClass = model.getElements(new TypeFilter<CtClass<?>>(CtClass.class))
            .stream()
            .filter(c -> c.getSimpleName().equals(publicClassName))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Public class " + publicClassName + " not found"));
    
        // rename
        mainClass.setSimpleName(newClassName);
        CtTypeReference<?> newTypeRef = mainClass.getReference();
    
        // constructor calls
        for (CtConstructorCall<?> call : model.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (call.getType().getSimpleName().equals(publicClassName)) {
                call.setType(newTypeRef);
            }
        }
    
        // type refs
        for (CtTypeReference<?> ref : model.getElements(new TypeFilter<>(CtTypeReference.class))) {
            if (ref.getSimpleName().equals(publicClassName)) {
                ref.setSimpleName(newClassName);
            }
        }
    
        // type access (e.g., Test15.static())
        for (CtTypeAccess<?> typeAccess : model.getElements(new TypeFilter<>(CtTypeAccess.class))) {
            if (typeAccess.getAccessedType() != null
                    && publicClassName.equals(typeAccess.getAccessedType().getSimpleName())) {
                typeAccess.setAccessedType((CtTypeReference) newTypeRef);
            }
        }
    
        // `.class` modeled structurally
        for (CtFieldAccess<?> fa : model.getElements(new TypeFilter<>(CtFieldAccess.class))) {
            if (fa.getTarget() instanceof CtTypeAccess) {
                CtTypeAccess<?> ta = (CtTypeAccess<?>) fa.getTarget();
                if (ta.getAccessedType() != null
                        && publicClassName.equals(ta.getAccessedType().getSimpleName())) {
                    ta.setAccessedType((CtTypeReference) newTypeRef);
                }
            }
        }
    
        // pretty print only top-level types from the same CU
        DefaultJavaPrettyPrinter printer = new DefaultJavaPrettyPrinter(factory.getEnvironment());
        StringBuilder sb = new StringBuilder();
    
        final java.io.File mainFile =
            (mainClass.getPosition() != null && mainClass.getPosition().isValidPosition())
                ? mainClass.getPosition().getFile()
                : null; // <-- final & assigned once
    
        List<CtType<?>> topLevels = model.getElements(new TypeFilter<>(CtType.class));
        topLevels.removeIf(t ->
            t.getDeclaringType() != null
            || t.getPosition() == null
            || !t.getPosition().isValidPosition()
            || (mainFile != null && !mainFile.equals(t.getPosition().getFile()))
        );
    
        topLevels.sort((a, b) -> Integer.compare(
            a.getPosition().getSourceStart(),
            b.getPosition().getSourceStart()
        ));

        for (CtType<?> t : topLevels) {
            sb.append(printer.prettyprint(t)).append("\n\n");
        }
    
        String modifiedSource = sb.toString();
        String mutatedFileName = newClassName + ".java";
        Path mutatedFilePath = Path.of(this.seedpoolDir, mutatedFileName);
    
        tc.setName(newClassName);
        tc.setPath(mutatedFilePath.toString());
    
        try {
            Files.write(mutatedFilePath, modifiedSource.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warning("Error writing mutated file: " + e.getMessage());
        }
    
        return tc;
    }

    private String allocateDeterministicName(TestCase parentTestCase, TestCase newTestCase) {
        int nextOrdinal = parentTestCase.getTimesSelected() + 1;
        return String.format(Locale.ROOT, "T%08d_%04d", newTestCase.getId(), nextOrdinal);
    }

}
