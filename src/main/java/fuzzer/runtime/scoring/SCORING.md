# Scoring modes

All scorers set `hashedOptVector` from the **merged** optimization counts across the whole test case (bucketed via `AbstractScorer.bucketCounts`). “Hot method/class” metadata is still the highest-scoring individual method for that mode; it does not affect corpus grouping.

Notation: per-feature counts `c_i` (merged across methods unless stated), global average frequency `avg_i`, global feature freq `F_i`, global pair freq `F_{ij}`, total runs `N` (or neutral `N=1` for seeds), `log` is natural log, `lift_cap=8`, `alpha=0.1`, `seen_weight=0.2`, `single_feature_weight=0.5`, `seen_pair_weight=0.05`.

## PF-IDF
- **Source:** Score uses merged counts across all methods; per-method PF-IDF is computed only to pick the hot method.
- **Per-method scan:** also computes a PF-IDF per method to pick the hot method.
- **Lift:** `lift_i = min( c_i / (avg_i + eps), lift_cap )`, `eps=1e-6`.
- **Pair term:** for each pair with `c_i>0`, `c_j>0`, `pairScore_ij = (sqrt(lift_i * lift_j) - 1) * idf_ij` where `idf_ij = log((N+1)/(F_{ij}+1)) / log(N+1)` (neutral seeds use `N=1`, `idf = log(2)/log(2) = 1`).
- **Score:** arithmetic mean of positive `pairScore_ij`; 0 if none are positive.
- **Measures:** Co-occurrence lift of rare optimization pairs, down-weighted by how common the pair is in prior runs.

## Uniform
- **Source:** Merged counts (score constant).
- **Score:** always `1.0` (ignores counts). Hot method unused.
- **Measures:** Provides a constant signal; hashed/bucketed vector still records coverage shape for corpus grouping.

## Absolute Count
- **Source:** Merged counts for score and hashed vector; hot method chosen by per-method total.
- **Score:** `score = sum_i max(c_i, 0)`.
- **Hot method:** method with largest per-method total.
- **Measures:** Total optimization activity (volume), independent of rarity/diversity.

## Novel Feature Bonus
- **Source:** Merged counts for score and hashed vector; hot method chosen by per-method formula.
- **Unseen weight:** unseen if `F_i == 0`.
- **Score:** `score = unseen_counts + alpha * total_counts`, where `unseen_counts = sum_{i: F_i=0} c_i`, `total_counts = sum_i c_i`, `alpha = 0.1`.
- **Hot method:** maximizes same formula on its own counts.
- **Measures:** Preference for triggering previously unseen optimizations, with a small bonus for overall activity.

## Pair Coverage
- **Source:** Merged counts for score and hashed vector; hot method chosen by per-method formula.
- **Present set:** `P = { i | c_i > 0 }`.
- **New pairs:** pairs `(i,j)` with `F_{ij} == 0`.
- **Score:** `score = new_pairs + single_feature_weight * unseen_feature_occurrences + seen_pair_weight * seen_pair_occurrences`, where:
  - `new_pairs = |{(i,j) in P, i<j : F_{ij}=0}|`
  - `unseen_feature_occurrences = sum_{i in P, F_i=0} c_i`
  - `seen_pair_occurrences = sum_{(i,j) in P, i<j, F_{ij}>0} (c_i + c_j)`
- Clamp scores `< 0.1` to `0`.
- **Hot method:** maximizes same formula per method.
- **Measures:** Discovery of brand-new pairs; also rewards hitting unseen features and (lightly) reinforcing seen pairs.

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
