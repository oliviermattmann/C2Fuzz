package fuzzer.io;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import fuzzer.model.MethodOptimizationVector;
import fuzzer.model.OptimizationVector;
import fuzzer.model.OptimizationVectors;



public class JVMOutputParser {
    private static final Logger LOGGER = LoggingConfig.getLogger(JVMOutputParser.class);


    public static OptimizationVectors parseJVMOutput(String jvmOutput) {
        ArrayList<MethodOptimizationVector> vectors = parseStructuredC2Output(jvmOutput);
        OptimizationVector merged;

        if (!vectors.isEmpty()) {
            merged = mergeOptimizationVectors(vectors);
        } else {
            merged = new OptimizationVector();
        }

        return new OptimizationVectors(vectors, merged);
    }


    private static ArrayList<MethodOptimizationVector> parseStructuredC2Output(String jvmOutput) {
        ArrayList<MethodOptimizationVector> vectors = new ArrayList<>();

        // skip other output before first OPTS_START
        int index = jvmOutput.indexOf("OPTS_START");
        if (index < 0) {
            return vectors; // empty
        }
        jvmOutput = jvmOutput.substring(index);
        String[] segments = jvmOutput.split("OPTS_START");
        
        for (String seg : segments) {
            // lines inside a block
            List<String> lines = new ArrayList<>();

            if (seg == null || seg.trim().isEmpty()) {
                continue;
            }

            try (Scanner sc = new Scanner(seg)) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.equals("OPTS_END")) break;
                    lines.add(trimmed);
                }
            }
            if (lines.isEmpty()) {
                continue;
            }
            // example header:
            // Opts|LoopOptShowcase|exercise|(II)I|OSR|4|23
            // ---- Parse header: Opts;class;method;OSR|non-OSR
            String header = lines.get(0);
            if (!header.startsWith("Opts|")) {
                LOGGER.warning("Malformed optimization block header; no opts prefix: " + header);
                continue;
            }
            String[] parts = header.split("\\|");
            
            String className       = parts[1].trim();
            String methodName      = parts[2].trim();
            String methodSignature = parts[3].trim();
            String compilationType = parts[4].trim();
            int entryBci = parts[5].trim().isEmpty() ? -1 : Integer.parseInt(parts[5].trim());   
            int compileId = Integer.parseInt(parts[6].trim());

            // ---- Initialize feature map (all known features set to 0)
            OptimizationVector features = new OptimizationVector();

            // ---- Parse key=value lines
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                int eq = line.indexOf('=');
                if (eq <= 0 || eq == line.length() - 1) continue; // skip invalid
                String key = line.substring(0, eq).trim();
                String valStr = line.substring(eq + 1).trim();

                try {
                    int val = Integer.parseInt(valStr);
                    features.addCount(OptimizationVector.FeatureFromName(key), val); 
                } catch (NumberFormatException ignored) {
                    LOGGER.severe("failed to parse optimization feature value: " + line);  
                }
            }

            vectors.add(new MethodOptimizationVector(className, methodName, methodSignature, compilationType, entryBci, compileId, features));
        }

        return vectors;
    }

    private static OptimizationVector mergeOptimizationVectors(List<MethodOptimizationVector> vectors) {
        OptimizationVector merged = new OptimizationVector();
        if (vectors == null || vectors.isEmpty()) {
            return merged;
        }

        for (MethodOptimizationVector vector : vectors) {
            int[] optimizations = vector.getOptimizations().counts;
            for (int i = 0; i < Math.min(optimizations.length, merged.NUM_FEATURES); i++) {
                merged.addCount(OptimizationVector.FeatureFromIndex(i), optimizations[i]);
            }
        }
        return merged;
    }
}
