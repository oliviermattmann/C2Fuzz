# Mutator Catalog

Each section describes how a mutator selects candidates, the high‑level behavior it injects, a simplified before/after code sketch, and the HotSpot/Graal optimization(s) it is meant to exercise.

Coverage notes:
- All active non-seed mutators (`MutatorType` minus `SEED`) are documented below.

## LoopUnswitchingEvokeMutator
- **Candidates** – Standalone, non-final assignments where `safeToAddLoops(..., 2)` allows injecting two extra loops. Prefers the current hot method, falls back to the surrounding class or the entire model, with a 20 % chance to explore globally up front.
- **Behavior** – Replaces the assignment with a double nested `for` (outer `i`, inner `j`) and a `switch(i)` that executes the original statement only in one case, then replays the assignment again after the loops.
- **Before → After**
```java
result = expensive(input);

int Mi123 = 4, Nj123 = 8;
for (int i123 = 0; i123 < Mi123; i123++) {
    for (int j123 = 0; j123 < Nj123; j123++) {
        switch (i123) {
            case -1, -2, -3 -> break;
            case 0 -> result = expensive(input);
        }
    }
}
result = expensive(input);
```
- **Intention** – Forces the compiler to consider loop unswitching and case splitting; the duplicated switch-controlled body stresses invariant hoisting and code duplication heuristics.

## LoopPeelingEvokeMutator
- **Candidates** – Assignments that sit directly in a block and allow adding one extra loop (`safeToAddLoops(..., 1)`), starting from the hot method/class with the same exploration fallback.
- **Behavior** – Wraps the assignment inside a counted loop guarded by `if (i < 10)` and “peels” another copy after the loop. It also injects a loop bound local (`Nxxxx`) to provide a scaling knob.
- **Before → After**
```java
sum = combine(sum, arr[i]);

int Ni42 = 32;
for (int i42 = 0; i42 < Ni42; i42++) {
    if (i42 < 10) {
        sum = combine(sum, arr[i]);
    }
}
sum = combine(sum, arr[i]);
```
- **Intention** – Creates classic loop peeling scenarios so C2/Graal can duplicate the first few iterations outside the loop and compare profitability.

## LoopUnrollingEvokeMutator
- **Candidates** – Standalone assignments that can tolerate one injected loop. Selection mirrors LoopPeeling with exploration and fallbacks.
- **Behavior** – Wraps the assignment in a helper block that runs it once, declares a final trip-count (`Nxxxx`), and then executes the assignment inside a loop from `1` to `Nxxxx`.
- **Before → After**
```java
dst[k] = src[k] + bias;

dst[k] = src[k] + bias;
final int Nk17 = 32;
for (int i17 = 1; i17 < Nk17; i17++) {
    dst[k] = src[k] + bias;
}
```
- **Intention** – Encourages loop unrolling and repetition removal by feeding identical bodies guarded by a counted loop into the optimizer.

## DeadCodeEliminationEvoke
- **Candidates** – Any assignment located in the hot method/class (or anywhere via global exploration).
- **Behavior** – Inserts `if (false) { assignmentClone; }` immediately before the real assignment.
- **Before → After**
```java
value = calc();

if (false) { value = calc(); }
value = calc();
```
- **Intention** – Provides obvious dead code so the optimizer’s DCE and unreachable-code pruning passes are stressed.

## DeoptimizationEvoke
- **Candidates** – Standalone assignments that can host one injected loop. Uses the same hot-method-first policy with exploration.
- **Behavior** – Surrounds the chosen assignment with a warmup profiling loop that repeatedly calls `toString()` on a pseudo “hot” object, runs the assignment only on the last iteration, mutates the “hot” object via `Integer.valueOf`, calls `toString()` again, and finally repeats the assignment.
- **Before → After**
```java
state = compute(state);

final int N999 = 32;
Object object999 = "hot";
for (int i999 = 0; i999 < N999; i999++) {
    object999.toString();
    if (i999 == N999 - 1) {
        state = compute(state);
    }
}
object999 = Integer.valueOf(1);
object999.toString();
state = compute(state);
```
- **Intention** – Forces profile pollution and object shape changes so C2/Graal must handle sudden invalidation/deoptimization scenarios around the guarded assignment.

## LockCoarseningEvoke
- **Candidates** – Existing `synchronized` blocks whose bodies contain ≥2 statements and no early-exit control flow.
- **Behavior** – Splits the synchronized body at a random statement index and emits two consecutive `synchronized` blocks that share the same monitor, each holding one slice of the original body.
- **Before → After**
```java
synchronized (lock) {
    a();
    b();
    c();
}

synchronized (lock) {
    a();
}
synchronized (lock) {
    b();
    c();
}
```
- **Intention** – Produces meaningful adjacent critical sections so the coarsening pass has to reason about merging non-empty bodies.

## LateZeroMutator
- **Candidates** – Standalone assignments where two loops can be injected.
- **Behavior** – Wraps the assignment with a warmup loop, a loop that repeatedly zeroes a temp variable, and an `if (zero == 0)` guard that executes the assignment in both branches.
- **Before → After**
```java
dst[i] = src[i];

int limit = 2;
for (; limit < 4; limit *= 2) { }
int zero = 34;
for (int peel = 2; peel < limit; peel++) { zero = 0; }
if (zero == 0) {
    dst[i] = src[i];
} else {
    dst[i] = src[i];
}
```
- **Intention** – Synthesizes “late zeroing” patterns seen in vectorized loops so the optimizer has to reason about redundant zero stores and invariant branches.

## TemplatePredicateMutator
- **Candidates** – Assignments whose LHS is an array store.
- **Behavior** – Introduces an opaque boolean local and wraps two versions of the assignment inside an `if/else`. The `else` branch rewrites the array index to `(idx + 1) % array.length`.
- **Before → After**
```java
a[i] = value;

boolean flag = /* opaque boolean expression */;
if (flag) {
    a[i] = value;
} else {
    a[((i) + 1) % a.length] = value;
}
```
- **Intention** – Recreates predicated array-store templates used in Tapir-style optimizations; this stresses predicate hoisting and alias reasoning.

## SplitIfStressMutator
- **Candidates** – `if` statements that have both `then` and `else` arms.
- **Behavior** – Replaces `if (cond) { A } else { B }` with a two-level decision tree that re-evaluates `cond` in both branches, effectively duplicating both arms twice.
- **Before → After**
```java
if (cond) { A(); } else { B(); }

if (cond) {
    if (cond) { A(); } else { B(); }
} else {
    if (cond) { B(); } else { A(); }
}
```
- **Intention** – Amplifies branch duplication so if-splitting, profile consistency, and CFG simplification paths are exercised.

## AutoboxEliminationEvoke
- **Candidates** – Primitive expressions (assignments, arguments, returns, binary operands) that are not in constant contexts and have a corresponding wrapper type.
- **Behavior** – Replaces the primitive expression with an explicit `Wrapper.valueOf(expr)` call.
- **Before → After**
```java
return sum;

return Integer.valueOf(sum);
```
- **Intention** – Forces the compiler to encounter redundant boxing that should be eliminated, stressing scalar replacement and intrinsics for `valueOf`.

## SinkableMultiplyMutator
- **Candidates** – Inner `for` loops (loops with another `CtLoop` as an ancestor).
- **Behavior** – Wraps the loop in a block that declares two counters, increments one at loop top, and sinks a multiply into the loop body guarded by a `try/catch` to keep the result live.
- **Before → After**
```java
for (...) {
    body();
}

int y123 = 0;
int toSink123 = 0;
for (...) {
    y123++;
    try {
        toSink123 = 23 * (y123 - 1);
    } catch (ArithmeticException ex) {
        toSink123 ^= ex.hashCode();
    }
    body();
}
int _sinkUse123 = toSink123;
```
- **Intention** – Introduces loop-invariant multiplies and exception edges so value-sinking and LICM (loop-invariant code motion) decisions are stressed.

## UnswitchScaffoldMutator
- **Candidates** – Any well-formed `for` loop.
- **Behavior** – Clones the loop body into “fast” and “slow” blocks, adds warmup/zapping scaffolding similar to `LateZero`, injects an early `if (flag) break`, and then uses an `if (zero == 0)` to select which clone to run.
- **Before → After**
```java
for (...) { body(); }

boolean flag = Opaque.toggle();
int limit = 2; for (; limit < 4; limit *= 2) { }
int zero = 34; for (int peel = 2; peel < limit; peel++) { zero = 0; }
for (...) {
    if (flag) break;
    if (zero == 0) {
        body_fast();
    } else {
        body_slow();
        int _unswitchMark = 0;
    }
}
```
- **Intention** – Builds a synthetic environment to trigger loop unswitching and branch cloning without depending on user code structure.

## EscapeAnalysisEvoke
- **Candidates** – Primitive expressions outside of already generated wrapper classes.
- **Behavior** – Generates a nested helper class `My<Type>` with a field `v`, constructor, and getter, then replaces the primitive expression with `new MyType(expr).v()` so the value “escapes” through object allocation.
- **Before → After**
```java
return sum;

class MyInteger {
    public int v;
    public MyInteger(int v) { this.v = v; }
    public int v() { return v; }
}
return new MyInteger(sum).v();
```
- **Intention** – Forces allocation of short‑lived wrapper objects to stress escape analysis, scalar replacement, and stack-allocation optimizations.

## RedundantStoreEliminationEvoke
- **Candidates** – All assignments in scope.
- **Behavior** – Clones the assignment and inserts the duplicate right before the original.
- **Before → After**
```java
arr[i] = value;

arr[i] = value;
arr[i] = value;
```
- **Intention** – Creates trivially redundant stores that should be folded by store-to-load forwarding and memory coalescing logic.

## AlgebraicSimplificationEvoke
- **Candidates** – Assignments whose RHS contains supported binary operators.
- **Behavior** – Picks a random binary op inside the assignment and rewrites it into an algebraically equivalent but more complex form (double NOT for booleans, or `expr + 0` for numerics).
- **Before → After**
```java
flag = a && b;

flag = !!(a && b);
```
- **Intention** – Ensures algebraic simplification and canonicalization passes see real work, helping reveal regressions in pattern-matching rewrites.

## LockEliminationEvoke
- **Candidates** – “Safe” assignments (not loop headers/updates) anywhere in scope.
- **Behavior** – Prefers wrapping the entire containing block (except loop bodies) inside one synthetic `synchronized (this)` or `synchronized (MyClass.class)`; if block wrapping is unsafe it falls back to wrapping just the assignment.
- **Before → After**
```java
{ // block surrounding the assignment
    stmt1();
    total = compute();
    stmt2();
}

{
    synchronized (this) {
        stmt1();
        total = compute();
        stmt2();
    }
}
```
- **Intention** – Creates broader artificial critical sections, giving lock-elimination passes more work than a single-statement lock.

## InlineEvokeMutator
- **Candidates** – Binary operators located inside methods.
- **Behavior** – Extracts the binary expression into a freshly generated helper method, parameterizing every literal/variable/use site as method parameters, then replaces the original expression with a call to the helper.
- **Before → After**
```java
return (x * y) + bias;

private int foo123(int p0, int p1, int p2) { return (p0 * p1) + p2; }
return foo123(x, y, bias);
```
- **Intention** – Encourages the inliner to inline the trivial helper and reconstruct the original expression, stressing call graph rebuilding and argument capture logic.

## IntToLongLoopMutator
- **Candidates** – Simple counted `for` loops whose induction variable is an `int` and is not used in array indices or int-only call sites/returns.
- **Behavior** – Widens the loop counter and all its uses inside the loop to `long`, adjusting literals along the way.
- **Before → After**
```java
for (int i = 0; i < n; i++) { body(i); }

for (long i = 0L; i < n; i++) { body(i); }
```
- **Intention** – Exercises long-based induction variable handling in range checks, loop optimizations, and strength reduction paths.

## ArrayToMemorySegmentMutator
- **Candidates** – Method-local 1D primitive/wrapper arrays with explicit `new` initializers and only straightforward reads/writes/`.length` uses.
- **Behavior** – Replaces the array with a `MemorySegment` allocated from a confined `Arena`, introduces local variables for the arena, length, and segment, rewrites reads/writes to `getAtIndex`/`setAtIndex`, and preserves inline initializers.
- **Before → After**
```java
int[] a = new int[] {1, 2, 3};
a[1] = 4;
int x = a.length;

var aArena = Arena.ofConfined();
int aLen = 3;
MemorySegment a = aArena.allocateArray(ValueLayout.JAVA_INT, (long) aLen);
a.setAtIndex(ValueLayout.JAVA_INT, 0L, 1);
a.setAtIndex(ValueLayout.JAVA_INT, 1L, 2);
a.setAtIndex(ValueLayout.JAVA_INT, 2L, 3);
a.setAtIndex(ValueLayout.JAVA_INT, 1L, 4);
int x = aLen;
```
- **Intention** – Drives foreign-memory access paths and JIT lowering for `MemorySegment` array-style usage.

## ArrayMemorySegmentShadowMutator
- **Candidates** – Method-local 1D primitive/wrapper arrays with explicit `new` initializers that are not reassigned.
- **Behavior** – Keeps the original array but adds a `MemorySegment` shadow via `MemorySegment.ofArray(array)` and a cached length local, rewrites `length` reads to the cached value, mirrors each simple store with a sibling `setAtIndex` call, and mirrors reads with a side-effecting `getAtIndex` call.
- **Before → After**
```java
int[] a = new int[] {1, 2, 3};
int v = a[i];
a[i] = v + 1;

MemorySegment aSeg = MemorySegment.ofArray(a);
int aLen = a.length;
int v = a[i];
aSeg.getAtIndex(ValueLayout.JAVA_INT, (long) (i));
a[i] = v + 1;
aSeg.setAtIndex(ValueLayout.JAVA_INT, (long) (i), v + 1);
```
- **Intention** – A permissive, low-disruption mutator that drives foreign-memory array access even when the original array usage is complex; by mirroring instead of replacing, it avoids many skips while still exercising `MemorySegment` get/set lowering.
