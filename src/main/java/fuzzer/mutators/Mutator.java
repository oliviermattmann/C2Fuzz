package fuzzer.mutators;

public interface Mutator {
    // Define mutation methods here
    MutationResult mutate(MutationContext ctx);
    boolean isApplicable(MutationContext ctx);
}
