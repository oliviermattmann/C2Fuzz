package fuzzer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

import fuzzer.util.ExecutionResult;
import fuzzer.util.GraphNodeMap;
import fuzzer.util.GraphParser;
import fuzzer.util.GraphResult;
import fuzzer.util.JVMOutputParser;
import fuzzer.util.LoggingConfig;
import fuzzer.util.OptFeedbackMap;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;


public class Evaluator implements Runnable{
    private final GlobalStats globalStats;
    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCaseResult> evaluationQueue;
    private final JVMOutputParser parser;
    private final GraphParser graphParser;
    private final InterestingnessScorer scorer;

    private static final Logger LOGGER = LoggingConfig.getLogger(Evaluator.class);

    public Evaluator(BlockingQueue<TestCaseResult> evaluationQueue, BlockingQueue<TestCase> mutationQueue, GlobalStats globalStats) {
        this.globalStats = globalStats;
        this.evaluationQueue = evaluationQueue;
        this.mutationQueue = mutationQueue;
        this.parser = new JVMOutputParser();
        this.graphParser = new GraphParser();
        this.scorer = new InterestingnessScorer(globalStats, 
            new ArrayList<>(OptFeedbackMap.newFeatureMap().keySet()), 
            new ArrayList<>(GraphNodeMap.newFeatureMap().keySet()), 
            5_000_000_000L/*s*/); // TODO pass real params currently set to 5s


    }

   
    @Override
    public void run() {
        LOGGER.info("Evaluator started.");

        while (true) { 
            try {
                TestCaseResult tcr = evaluationQueue.take();

                // use average exec time to score the testcase 

                //long slowestTime = Math.max(tcr.intExecutionResult().executionTime(), tcr.jitExecutionResult().executionTime());
                long avgTime = (tcr.intExecutionResult().executionTime() + tcr.jitExecutionResult().executionTime()) / 2;


                TestCase testCase = tcr.testCase();
                ExecutionResult intResult = tcr.intExecutionResult();
                ExecutionResult jitResult = tcr.jitExecutionResult();

                LOGGER.fine(String.format("ExecutionResult for test case %s", testCase.getName()));
                LOGGER.fine(String.format("Exit codes same: %b", intResult.exitCode() == jitResult.exitCode()));
                LOGGER.fine(String.format("Stderr same: %b", intResult.stderr().equals(jitResult.stderr())));

                // check for timeouts
                if (intResult.timedOut()) {
                    LOGGER.severe(String.format("Interpreter Timeout for test case %s: int timed out=%b, jit timed out=%b", testCase.getName(), intResult.timedOut(), jitResult.timedOut()));
                    deleteAndArchiveTestCase(testCase, "Interpreter timed out... test case too slow");
                    continue;
                } else  {
                    // interpreter did not time out, check whether jit did, if so then there might be a performance bug
                    if (jitResult.timedOut()) {
                        LOGGER.severe(String.format("JIT Timeout for test case %s: int timed out=%b, jit timed out=%b", testCase.getName(), intResult.timedOut(), jitResult.timedOut()));
                        globalStats.foundBugs.increment();
                        saveBugInducingTestCase(tcr.testCase(), "JIT timed out, interpreter did not", intResult, jitResult);
                        continue;
                    }
                }

                // both executions went through without timeout

                // check for different exit codes
                if (intResult.exitCode() != jitResult.exitCode()) {
                    LOGGER.severe(String.format("Different exit codes for test case %s: int=%d, jit=%d", testCase.getName(), intResult.exitCode(), jitResult.exitCode()));
                    globalStats.foundBugs.increment();
                    saveBugInducingTestCase(tcr.testCase(), "Different exit codes", intResult, jitResult);
                    continue;
                }

                // from here we can assume the same exit code
                int exitCode = intResult.exitCode();
                if (exitCode != 0) {
                    LOGGER.severe(String.format("Non-zero exit code %d for test case %s", exitCode, testCase.getName()));
                    if (!tcr.isCompilable()) {
                        deleteAndArchiveTestCase(tcr.testCase(), "Non-compilable test case with non-zero exit code, last mutation: " + tcr.testCase().getMutation());
                    } else if (intResult.timedOut() || jitResult.timedOut()) {
                        deleteAndArchiveTestCase(tcr.testCase(), "Timeout with non-zero exit code, last mutation: " + tcr.testCase().getMutation());
                    } else {
                        deleteAndArchiveTestCase(tcr.testCase(), "Non-zero exit code (REASON UNKNOWN)");
                    }

                    continue;
                }

                // if both exit codes are zero, we should further compare the results of the program (ie wrong output caused by optimizations)
                // TODO find a way to compare results (ie extract them from stdout; currently contaminated with flag output)




                // The test case did not induce a bug, but we still want to know if it is interesting
                // for now we check if there are new optimizations compared to the parent test case (if it exists)
                Map<String, Integer> parentOptCounts = testCase.getParentOccurences();
                Map<String, Integer> jitOptCounts = parser.parseOutput(jitResult.stdout());
                if (parentOptCounts != null) {
                    // check whether any new optimizations were observed
                    int totalParent = parentOptCounts.values().stream().mapToInt(Integer::intValue).sum();
                    int totalCurrent = jitOptCounts.values().stream().mapToInt(Integer::intValue).sum();

                    if (totalCurrent >= totalParent) {
                        LOGGER.fine("New or same amount of optimizations observed in test case " + testCase.getName() + ": parent total " + totalParent + ", current total " + totalCurrent);
                        
                    } else {
                        // no new optimizations, discard test case
                        LOGGER.fine("No new optimizations observed in test case " + testCase.getName() + ": parent total " + totalParent + ", current total " + totalCurrent + "; applied mutation: " + testCase.getMutation());
                        // deleteTestCase(testCase);
                        // continue;
                    }
                } 
                // parse the ideal graphs
               // List<GraphResult> graphResults = graphParser.parseGraphs(tcr.testCase().getPath().replace(".java", ".xml"));
                List<GraphResult> graphResults = new ArrayList<>();

                // compute the interestingness score for the priority queue
                double score = scorer.score(jitOptCounts, graphResults, avgTime);

                // clean up class files
                cleanUpClassFiles(testCase);

                globalStats.recordExecTimeNanos(avgTime);
                globalStats.recordTest(score);
                testCase.setScore(score);
                mutationQueue.put(testCase);
                LOGGER.info(String.format("Test case %s scored %f and added to mutation queue.", testCase.getName(), score));

            } catch (Exception e) {


            }
        }
    }

    void saveBugInducingTestCase(TestCase testCase, String reason, ExecutionResult intResult, ExecutionResult jitResult) {
        // for now move testcase to bugs/ directory
        // later collect all info plus crash output
        Path javaFile = Path.of(testCase.getPath());
        Path classFile = Path.of(testCase.getPath().replace(".java", ".class"));
        Path xmlFile = Path.of(testCase.getPath().replace(".java", ".xml"));

        //move files to bug directory, create if it does not exist
        Path bugDir = javaFile.getParent().resolve("bugs");
        try {
            if (!java.nio.file.Files.exists(bugDir)) {
                java.nio.file.Files.createDirectory(bugDir);
            }
        } catch (IOException e) {
            System.err.println("Failed to create bugs directory: " + e.getMessage());
            return;
        }

        // move files
        try {
            java.nio.file.Files.move(javaFile, bugDir.resolve(javaFile.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.move(classFile, bugDir.resolve(classFile.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (java.nio.file.Files.exists(xmlFile)) {
                java.nio.file.Files.move(xmlFile, bugDir.resolve(xmlFile.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Failed to move test case files to bugs directory: " + e.getMessage());
            return;
        }

        // add an additional text file with the reason for saving the test case
        // it should include the exit codes, stderr and stdout of both runs
        String infoFileName = testCase.getName().replace(".java", "") + "_info.txt";
        LOGGER.severe("Info file name: " + infoFileName);

        Path infoFile = bugDir.resolve(infoFileName);
        List<String> infoLines = new ArrayList<>();
        infoLines.add("Reason: " + reason);
        infoLines.add("Test case: " + testCase.getName());
        infoLines.add("Interpreter exit code: " + intResult.exitCode());
        infoLines.add("JIT exit code: " + jitResult.exitCode());
        infoLines.add("Interpreter stdout:\n" + intResult.stdout());
        infoLines.add("JIT stdout:\n" + jitResult.stdout());
        infoLines.add("Interpreter stderr:\n" + intResult.stderr());
        infoLines.add("JIT stderr:\n" + jitResult.stderr());

        try {
            LOGGER.severe("Writing info file for bug-inducing test case: " + infoFile.toString());
            java.nio.file.Files.write(infoFile, infoLines);
        } catch (IOException e) {
            System.err.println("Failed to write info file for bug-inducing test case: " + e.getMessage());
            LOGGER.severe("Failed to write info file for bug-inducing test case: " + e.getMessage());
        }


    }


    void deleteTestCase(TestCase testCase) {
        Path javaFile = Path.of(testCase.getPath());
        Path classFile = Path.of(testCase.getPath().replace(".java", ".class"));
        Path xmlFile = Path.of(testCase.getPath().replace(".java", ".xml"));
        
        // delete all class files (including inner classes)
        java.nio.file.DirectoryStream.Filter<Path> filter = entry -> {
            String fileName = entry.getFileName().toString();
            return fileName.startsWith(testCase.getName().replace(".java", "")) && fileName.endsWith(".class");
        };
        try (var stream = java.nio.file.Files.newDirectoryStream(javaFile.getParent(), filter)) {
            for (Path entry : stream) {
                java.nio.file.Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            System.err.println("Failed to delete inner class files for test case: " + testCase.getName());
        }
        // delete main class file and xml file if it exists
        try {
            java.nio.file.Files.deleteIfExists(xmlFile);
            java.nio.file.Files.deleteIfExists(classFile);
            java.nio.file.Files.deleteIfExists(javaFile);
        } catch (IOException e) {
            System.err.println("Failed to delete files for test case: " + testCase.getName());
        }


    }


    void deleteAndArchiveTestCase (TestCase testCase, String reason) {
        Path javaFile = Path.of(testCase.getPath());
        Path classFile = Path.of(testCase.getPath().replace(".java", ".class"));
        Path xmlFile = Path.of(testCase.getPath().replace(".java", ".xml"));
        
        // delete all class files (including inner classes)
        java.nio.file.DirectoryStream.Filter<Path> filter = entry -> {
            String fileName = entry.getFileName().toString();
            return fileName.startsWith(testCase.getName().replace(".java", "")) && fileName.endsWith(".class");
        };
        try (var stream = java.nio.file.Files.newDirectoryStream(javaFile.getParent(), filter)) {
            for (Path entry : stream) {
                java.nio.file.Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            System.err.println("Failed to delete inner class files for test case: " + testCase.getName());
        }
        // delete main class file and xml file if it exists
        try {
            java.nio.file.Files.deleteIfExists(xmlFile);
            java.nio.file.Files.deleteIfExists(classFile);
        } catch (IOException e) {
            System.err.println("Failed to delete files for test case: " + testCase.getName());
        }

        // before moving the java file, insert the reason for deletion as a block comment at the top of the file
        try {
            List<String> lines = java.nio.file.Files.readAllLines(javaFile);
            lines.add(0, "/* " + reason + " */");
            java.nio.file.Files.write(javaFile, lines);
        } catch (IOException e) {
            System.err.println("Failed to annotate test case with reason: " + testCase.getName());
        }
        // move the java file to the archive directory
        Path archiveDir = javaFile.getParent().resolve("archive");
        try {
            java.nio.file.Files.move(javaFile, archiveDir.resolve(javaFile.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to move test case to archive: " + testCase.getName());
        }
    }

    void cleanUpClassFiles(TestCase testCase) {
        // TODO also delete class files which are not inner classes (don't have testcase name in them)
        Path classFile = Path.of(testCase.getPath().replace(".java", ".class"));
        Path xmlFile = Path.of(testCase.getPath().replace(".java", ".xml"));
        
        // delete all class files (including inner classes)
        java.nio.file.DirectoryStream.Filter<Path> filter = entry -> {
            String fileName = entry.getFileName().toString();
            return fileName.startsWith(testCase.getName().replace(".java", "")) && fileName.endsWith(".class");
        };
        try (var stream = java.nio.file.Files.newDirectoryStream(classFile.getParent(), filter)) {
            for (Path entry : stream) {
                java.nio.file.Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            System.err.println("Failed to delete class files for test case: " + testCase.getName());
        }
        // also delete the xml file if it exists
        try {
            java.nio.file.Files.deleteIfExists(xmlFile);
        } catch (IOException e) {
            System.err.println("Failed to delete files for test case: " + testCase.getName());
        }
    }
}