package fuzzer.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;




public class JVMOutputParser {

    private final Map<String, Pattern> regexPatterns;
    //private final Map<String, Integer> occurrences;

    public JVMOutputParser() {
        this.regexPatterns = new LinkedHashMap<>();
        initParser();
    }


    /*
     * Initialize the parser with regex patterns to track various JVM optimizations.
     * Add new patterns here
     * TODO add c2 compatible patterns
     */
    private void initParser() {
        addRegex("Loop Unrolling", "Unroll [0-9]+");
        addRegex("Loop Peeling", "(Partial)?Peel\s{2}");
        addRegex("Parallel Induction Variables", "Parallel IV: [0-9]+");

        // gpt gave me this not 100% sure it works as intended TODO verify:
        // "(?:Split|RegionSplit-If)\\s+\\d+\\s+\\S+\\s+through\\s+\\d+\\s+Phi\\s+in\\s+\\d+\\s+RegionSplit-If"
        addRegex("Split if", "(?:Split|RegionSplit-If)\\s+\\d+\\s+\\S+\\s+through\\s+\\d+\\s+Phi\\s+in\\s+\\d+\\s+RegionSplit-If");

        //addRegex("Loop Unswitching", "Loop unswitching orig: [0-9]+ @ [0-9]+  new:");
        addRegex("Loop Unswitching", "(?i)\\bLoop unswitching\\b");
        addRegex("Conditional Expression Elimination", "[0-9]+\\. CEE in B[0-9]+ (B[0-9]+ B[0-9]+)");
        addRegex("Function Inlining", "inline(?:\\s+\\(hot\\))?\\s*$");
        //addRegex("Function Inlining", "inline (\\s\\(hot\\))?$"); // "inline(?:\s+\(hot\))?\s*$"
        addRegex("Deoptimization", "UNCOMMON TRAP");
        addRegex("Escape Analysis", "(UnknownEscape|NoEscape|GlobalEscape|ArgEscape)");

        addRegex("Eliminate Locks", "(Eliminated: [0-9]+ (Lock|Unlock)|unique_lock)");
        addRegex("Locks Coarsening", "(Coarsened [0-9]+ unlocks|unbalanced coarsened)");
        addRegex("Conditional Constant Propagation", "CCP: [0-9]+");
        addRegex("Eliminate Autobox", "Eliminated: [0-9]+ (Allocate|AllocateArray)*");
        addRegex("Block Elimination", "(replaced If and IfOp|merged B[0-9]+)");

        addRegex("simplify Phi Function", "try_merge for block B[0-9]+ successful");
        addRegex("Canonicalization", "^canonicalized to:$");
        addRegex("Null Check Elimination", "Done with null check elimination for method");
        addRegex("Range Check Elimination", "Range check for instruction [0-9]+ eliminated");
        addRegex("Optimize Ptr Compare", "\\+\\+\\+\\+ Replaced: [0-9]+");
    }

    // Add a regex pattern to track
    public void addRegex(String name, String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
        regexPatterns.put(name, pattern);
        
    }

    // Parse the JVM output and count occurrences
    public Map<String, Integer> parseOutput(String jvmOutput) {
        Map<String, Integer> occurrences = OptFeedbackMap.newFeatureMap();
        for (Map.Entry<String, Pattern> entry : regexPatterns.entrySet()) {
            String name = entry.getKey();
            Pattern pattern = entry.getValue();
            Matcher matcher = pattern.matcher(jvmOutput);

            int count = 0;
            while (matcher.find()) {
                count++;
            }
            occurrences.put(name, occurrences.get(name) + count);
        }
        return occurrences;
    }
}