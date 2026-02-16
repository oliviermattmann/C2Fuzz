# Scoring modes

All scorers set `hashedOptVector` from the **merged** optimization counts across the whole test case (bucketed via `AbstractScorer.bucketCounts`). “Hot method/class” metadata is still the highest-scoring individual method for that mode; it does not affect corpus grouping.

Notation: per-feature counts `c_i` (merged across methods unless stated), global average frequency `avg_i`, global feature freq `F_i`, global pair freq `F_{ij}`, total runs `N` (or neutral `N=1` for seeds), `log` is natural log, `seen_weight=0.2`.

All computed scores are compressed via `log1p(score)` before being stored, compared, or reported.

## PF-IDF
- **Source:** Score uses merged counts across all methods; per-method PF-IDF is computed only to pick the hot method.
- **Per-method scan:** also computes a PF-IDF per method to pick the hot method.
- **Lift:** `lift_i = c_i / (avg_i + eps)`, `eps=1e-6`.
- **Pair term:** for each pair with `c_i>0`, `c_j>0`, `pairScore_ij = (sqrt(lift_i * lift_j) - 1) * idf_ij` where `idf_ij = log((N+1)/(F_{ij}+1)) / log(N+1)` (neutral seeds use `N=1`, `idf = log(2)/log(2) = 1`).
- **Score:** arithmetic mean of positive `pairScore_ij`; 0 if none are positive.
- **Measures:** Co-occurrence lift of rare optimization pairs, down-weighted by how common the pair is in prior runs.

## Uniform
- **Source:** Merged counts (score constant).
- **Score:** always `1.0` (ignores counts). Hot method unused.
- **Measures:** Provides a constant signal; hashed/bucketed vector still records coverage shape for corpus grouping.

## Interaction Diversity
- **Source:** Merged counts for score and hashed vector; hot method chosen by per-method `(total - peak)`.
- **Score:** `score = max( sum_i c_i - max_i c_i , 0 )`.
- **Hot method:** method with largest `(total - peak)` on its own counts.
- **Measures:** Spread of optimizations across features; penalizes domination by a single feature.

## Interaction Pair Weighted
- **Source:** Merged counts for score and hashed vector; hot method chosen by per-method formula.
- **Normalization:** `norm_i = log1p(c_i) / sqrt(1 + F_i)`.
- **Weights:** For each `(i,j)` in present pairs, `w_ij = norm_i * norm_j`.
- **Score:** `score = newWeight + seen_weight * seenWeight`, where:
  - `newWeight = sum_{(i,j):F_{ij}=0} w_ij`
  - `seenWeight = sum_{(i,j):F_{ij}>0} w_ij`
  - If `score <= 0` but `totalWeight > 0`, fall back to `score = seen_weight * totalWeight`.
- Requires at least two present features; otherwise `0`.
- **Hot method:** maximizes same formula per method.
- **Measures:** Pair interactions emphasizing rare features/pairs, while still crediting seen pairs at reduced weight.
