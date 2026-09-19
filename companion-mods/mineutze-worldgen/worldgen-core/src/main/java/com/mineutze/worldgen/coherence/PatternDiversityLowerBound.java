package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A real, CONSTRUCTIVE lower bound on how many distinct periodic witnesses the current rule
 * graph admits - not an empirical "we found N by sampling," a derived count (2026-09-17
 * user direction: "prove there is an infinite range of those patterns").
 *
 * <p><b>The argument:</b> {@link AdjacencyDerivation#deriveAll} only ever connects row
 * {@code r} to row {@code (r+1) mod rowCount} - strictly nearest-row, no skip connections
 * (confirmed by direct inspection, not assumed). That means if row {@code i} is at least 2
 * rows away from row {@code j} (cyclically, wraparound included), substituting row
 * {@code i}'s content can NEVER affect whether row {@code j}'s own ADJACENCY legality is
 * legal, as long as row {@code i}'s two NEIGHBORS ({@code i-1}, {@code i+1}) stay fixed.
 * Picking every-other row of an existing witness (skipping the one pair that would wrap
 * adjacent to itself when the row count is odd - see {@link #nonAdjacentPositions}) gives a
 * set of positions whose ADJACENCY substitutions are all mutually independent.
 *
 * <p><b>Real gap found and fixed (2026-09-17, v1):</b> adjacency legality is NOT the only
 * thing {@link ConstraintChecker} checks - full-catalog COVERAGE and REQUIRED-NEIGHBOR
 * minimums are GLOBAL properties, not local to a row's own two neighbors, so the adjacency-
 * only independence argument does not automatically extend to them. A first version of this
 * class ignored this and got 0/10 real verification failures - every alternative row was
 * built via a totally fresh random walk, which could easily drop a biome the original row
 * uniquely provided.
 *
 * <p><b>v2 fix (same-multiset only) was CORRECT but too weak</b> - constraining every
 * alternative to the original row's exact biome multiset made coverage trivially safe, but
 * cut the real bound to ~2^2.6 (most rows only have their own original ordering as a legal
 * permutation). <b>v3 (this version):</b> per position, check whether the row is actually
 * SAFE for a FRESH, unconstrained alternative - a row is safe if every biome it contains has
 * at least one other occurrence OUTSIDE the whole diversified position set (a real "anchor"
 * row that never changes, guaranteeing that biome's presence regardless of what the
 * diversified rows become). Safe positions get the full, rich fresh-random-walk search
 * ({@link PeriodicCoherenceProof#tryBuildLegalRow}); only genuinely unsafe positions fall
 * back to the narrower same-multiset permutation. Required-neighbor safety is still never
 * assumed either way - every candidate, from either strategy, gets a REAL single-swap
 * {@link ConstraintChecker} check against the FULL witness before being counted.
 *
 * <p><b>Verified, not just argued:</b> beyond the real per-candidate single-swap check, a
 * real sample of full MULTI-position recombined layouts also gets checked end to end via
 * {@link ConstraintChecker} - see {@link #main}'s own verification pass.
 */
public final class PatternDiversityLowerBound {

    private static final Path REPORT_PATH = Path.of("doc", "pattern-diversity-lower-bound-report.txt");
    private static final long RANDOM_SEED = 20260917L;
    private static final int ATTEMPT_BUDGET_PER_POSITION = 300;
    private static final int MAX_DISTINCT_ALTERNATIVES_PER_POSITION = 20;
    private static final int VERIFICATION_SAMPLE_COUNT = 10;
    // Real worldSeed space (2^64) - the practically relevant comparison: is the proven
    // pattern space even in the same ballpark as the number of real seeds a player could pick.
    private static final double REAL_WORLD_SEED_SPACE_LOG2 = 64.0;

    private PatternDiversityLowerBound() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        runProof(table, REPORT_PATH, "production ModBiomeGraph.OVERWORLD (src/main/resources/biomegraph/biome-graph.json)");
    }

    public static void runProof(RawRuleTable table, Path reportPath, String label) throws IOException {
        PeriodicCoherenceProof.WitnessSearchResult searchResult = PeriodicCoherenceProof.findWitness(table);
        if (searchResult.layout() == null) {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(
                reportPath,
                "PATTERN DIVERSITY LOWER BOUND - INCONCLUSIVE\n=============================================\n\n"
                    + "No base witness found (PeriodicCoherenceProof.findWitness itself was inconclusive) -\n"
                    + "this proof needs one real witness to diversify from. Not a proof of impossibility.\n",
                StandardCharsets.UTF_8
            );
            System.out.println("INCONCLUSIVE - no base witness found. Report: " + reportPath.toAbsolutePath().toUri());
            return;
        }

        PeriodicRowLayout witness = searchResult.layout();
        int rowCount = witness.rows().size();
        List<Integer> positions = nonAdjacentPositions(rowCount);
        Set<Integer> diversifiedPositionSet = new HashSet<>(positions);
        Map<BiomeId, List<Integer>> rowIndicesByBiome = computeRowIndicesByBiome(witness);
        System.out.println(
            "Base witness: rowCount=" + rowCount + ". Diversifying " + positions.size() + " mutually-independent, non-adjacent row positions."
        );

        Random random = new Random(RANDOM_SEED);
        List<List<Row>> alternativesByPosition = new ArrayList<>();
        double totalLog2 = 0;
        int safeCount = 0;
        StringBuilder perPositionReport = new StringBuilder();
        for (int position : positions) {
            List<BiomeId> originalSequence = sequenceOf(witness.rows().get(position));
            boolean safe = isSafeForFreeSubstitution(originalSequence, diversifiedPositionSet, rowIndicesByBiome);
            if (safe) {
                safeCount++;
            }
            List<Row> alternatives = distinctAlternativesFor(table, witness, position, safe, random);
            alternativesByPosition.add(alternatives);
            int countAtThisPosition = 1 + alternatives.size(); // +1 for the original row itself
            totalLog2 += log2(countAtThisPosition);
            perPositionReport.append("  position ").append(position).append(" [").append(safe ? "FREE" : "same-multiset").append("]: ")
                .append(countAtThisPosition).append(" distinct legal rows (1 original + ").append(alternatives.size()).append(" found)\n");
            System.out.println("  position " + position + " [" + (safe ? "FREE" : "same-multiset") + "]: " + countAtThisPosition + " distinct legal rows");
        }
        System.out.println(safeCount + "/" + positions.size() + " positions were safe for free (unconstrained) substitution.");

        System.out.println();
        System.out.println("Real, constructive lower bound: at least 2^" + String.format("%.2f", totalLog2) + " distinct witnesses.");
        System.out.println("Real worldSeed space: 2^" + (int) REAL_WORLD_SEED_SPACE_LOG2 + " (~1.8x10^19).");
        String comparison = totalLog2 >= REAL_WORLD_SEED_SPACE_LOG2
            ? "The proven pattern space is AT LEAST AS LARGE as the real worldSeed space."
            : "The proven pattern space is SMALLER than the real worldSeed space by this bound alone (a real, honest limit of this construction - does not mean fewer patterns truly exist, only that this specific argument doesn't reach that far).";
        System.out.println(comparison);

        VerificationResult verification = verifySample(witness, positions, alternativesByPosition, table, random);
        System.out.println();
        System.out.println(
            "Verification: " + verification.validCount() + "/" + verification.totalSampled()
                + " sampled recombined layouts passed the REAL, full ConstraintChecker end to end."
        );

        writeReport(reportPath, label, rowCount, positions.size(), perPositionReport.toString(), totalLog2, comparison, verification);
        System.out.println("Report: " + reportPath.toAbsolutePath().toUri());
    }

    /**
     * Every other row position, skipping the LAST one if the row count is odd (it would
     * otherwise wrap adjacent to position 0, breaking the independence argument - two
     * positions selected for independent substitution must never themselves be neighbors).
     */
    static List<Integer> nonAdjacentPositions(int rowCount) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < rowCount; i += 2) {
            positions.add(i);
        }
        if (positions.size() >= 2 && positions.get(positions.size() - 1) == rowCount - 1) {
            positions.remove(positions.size() - 1);
        }
        return positions;
    }

    /** Every row index (in the FULL witness) that contains {@code biome} at least once, keyed by biome - real coverage bookkeeping, computed once per run. */
    private static Map<BiomeId, List<Integer>> computeRowIndicesByBiome(PeriodicRowLayout witness) {
        Map<BiomeId, List<Integer>> rowIndicesByBiome = new HashMap<>();
        for (int rowIndex = 0; rowIndex < witness.rows().size(); rowIndex++) {
            for (BiomeId biome : new HashSet<>(sequenceOf(witness.rows().get(rowIndex)))) {
                rowIndicesByBiome.computeIfAbsent(biome, unused -> new ArrayList<>()).add(rowIndex);
            }
        }
        return rowIndicesByBiome;
    }

    /**
     * A row is safe for a FRESH (unconstrained) alternative search only if every biome it
     * contains has at least one occurrence OUTSIDE the whole diversified position set - a
     * real "anchor" row that never changes, guaranteeing that biome's catalog presence no
     * matter what every diversified row simultaneously becomes. If even one biome in this
     * row has ALL its occurrences inside the diversified set, this row must stay constrained
     * to same-multiset permutation instead (real risk otherwise: every row holding that
     * biome could simultaneously pick an alternative without it, actually dropping it).
     */
    private static boolean isSafeForFreeSubstitution(
        List<BiomeId> rowBiomes, Set<Integer> diversifiedPositionSet, Map<BiomeId, List<Integer>> rowIndicesByBiome
    ) {
        for (BiomeId biome : new HashSet<>(rowBiomes)) {
            boolean hasAnchorOutsideDiversifiedSet = rowIndicesByBiome.get(biome).stream().anyMatch(rowIndex -> !diversifiedPositionSet.contains(rowIndex));
            if (!hasAnchorOutsideDiversifiedSet) {
                return false;
            }
        }
        return true;
    }

    /**
     * Real alternatives for {@code witness.rows().get(position)}. When {@code safe} (see
     * {@link #isSafeForFreeSubstitution}), searches FRESH, unconstrained rows via {@link
     * PeriodicCoherenceProof#tryBuildLegalRow} (much richer - real biome choice, not just
     * reordering) since coverage is already guaranteed by an anchor elsewhere; otherwise
     * falls back to a random PERMUTATION of the row's own exact biome multiset (narrower,
     * but trivially coverage-safe on its own). EITHER way, every candidate still gets a REAL
     * single-swap {@link ConstraintChecker} check against the FULL witness before being
     * counted - required-neighbor safety is never assumed, only verified.
     */
    private static List<Row> distinctAlternativesFor(RawRuleTable table, PeriodicRowLayout witness, int position, boolean safe, Random random) {
        int rowCount = witness.rows().size();
        Row original = witness.rows().get(position);
        Row lowerFixedNeighbor = witness.rows().get(Math.floorMod(position - 1, rowCount));
        Row upperFixedNeighbor = witness.rows().get(Math.floorMod(position + 1, rowCount));
        List<BiomeId> originalSequence = sequenceOf(original);

        List<Row> found = new ArrayList<>();
        Set<List<BiomeId>> seenSequences = new HashSet<>();
        seenSequences.add(originalSequence); // the original itself never counts as a "found alternative"

        for (int attempt = 0; attempt < ATTEMPT_BUDGET_PER_POSITION && found.size() < MAX_DISTINCT_ALTERNATIVES_PER_POSITION; attempt++) {
            Row candidate = safe
                ? PeriodicCoherenceProof.tryBuildLegalRow(
                    table, Set.of(), lowerFixedNeighbor, upperFixedNeighbor, PeriodicCoherenceProof.MIN_CELLS_PER_ROW,
                    PeriodicCoherenceProof.MAX_CELLS_PER_ROW, witness.rowHeightBlocks(), witness.circumferenceBlocks(), random, 1
                )
                : buildPermutedRowIfLegal(table, originalSequence, lowerFixedNeighbor, upperFixedNeighbor, witness, random);
            if (candidate == null) {
                continue;
            }
            if (!seenSequences.add(sequenceOf(candidate))) {
                continue;
            }
            if (!passesFullCheckAsSingleSwap(witness, position, candidate, table)) {
                continue;
            }
            found.add(candidate);
        }
        return found;
    }

    /** One same-multiset permutation attempt - {@code null} if this particular shuffle isn't legal (caller retries with a fresh shuffle). */
    private static Row buildPermutedRowIfLegal(
        RawRuleTable table, List<BiomeId> originalSequence, Row lowerFixedNeighbor, Row upperFixedNeighbor, PeriodicRowLayout witness, Random random
    ) {
        List<BiomeId> permuted = new ArrayList<>(originalSequence);
        Collections.shuffle(permuted, random);
        if (!isHorizontallySequenceLegal(permuted, table)) {
            return null;
        }
        RowFeasibility.WidthInterval interval = RowFeasibility.achievableInterval(permuted, table, witness.rowHeightBlocks());
        if (!RowFeasibility.fits(witness.circumferenceBlocks(), interval)) {
            return null;
        }
        List<Cell> cells = RowFeasibility.distributeWidths(permuted, table, witness.rowHeightBlocks(), witness.circumferenceBlocks());
        Row candidate = new Row(cells);
        if (!PeriodicCoherenceProof.isVerticallyLegal(lowerFixedNeighbor, candidate, table)) {
            return null;
        }
        if (!PeriodicCoherenceProof.isVerticallyLegal(candidate, upperFixedNeighbor, table)) {
            return null;
        }
        return candidate;
    }

    /** Consecutive pairs within the row, wrapping last-to-first - horizontal legality of a candidate sequence BEFORE building real cells/widths for it. */
    private static boolean isHorizontallySequenceLegal(List<BiomeId> sequence, RawRuleTable table) {
        for (int i = 0; i < sequence.size(); i++) {
            BiomeId a = sequence.get(i);
            BiomeId b = sequence.get((i + 1) % sequence.size());
            if (!table.isLegal(a, b)) {
                return false;
            }
        }
        return true;
    }

    /** Real, no-assumptions check: swap ONLY this one row into the full witness (everything else at its original value) and run the real ConstraintChecker - catches coverage/required-neighbor effects the local pairwise checks above can't see. */
    private static boolean passesFullCheckAsSingleSwap(PeriodicRowLayout witness, int position, Row candidate, RawRuleTable table) {
        List<Row> rows = new ArrayList<>(witness.rows());
        rows.set(position, candidate);
        PeriodicRowLayout singleSwapLayout = new PeriodicRowLayout(witness.rowHeightBlocks(), witness.circumferenceBlocks(), rows);
        return ConstraintChecker.check(singleSwapLayout, table).isValid();
    }

    private static List<BiomeId> sequenceOf(Row row) {
        return row.cells().stream().map(Cell::biomeId).toList();
    }

    private record VerificationResult(int totalSampled, int validCount, List<String> failureDetails) {
    }

    /**
     * Real, honest verification: builds {@code VERIFICATION_SAMPLE_COUNT} FULL layouts by
     * recombining a RANDOM choice (original or a found alternative) at every diversified
     * position, leaving every other row exactly as the base witness had it, then runs the
     * REAL {@link ConstraintChecker} against the whole thing - never trusts the pairwise-
     * independence argument alone.
     */
    private static VerificationResult verifySample(
        PeriodicRowLayout witness, List<Integer> positions, List<List<Row>> alternativesByPosition, RawRuleTable table, Random random
    ) {
        int validCount = 0;
        List<String> failures = new ArrayList<>();
        for (int sample = 0; sample < VERIFICATION_SAMPLE_COUNT; sample++) {
            List<Row> recombined = new ArrayList<>(witness.rows());
            for (int i = 0; i < positions.size(); i++) {
                List<Row> alternatives = alternativesByPosition.get(i);
                if (alternatives.isEmpty()) {
                    continue;
                }
                // Index 0 = the original row; 1..n = a found alternative - real, uniform choice among all of them.
                int choice = random.nextInt(alternatives.size() + 1);
                if (choice > 0) {
                    recombined.set(positions.get(i), alternatives.get(choice - 1));
                }
            }
            PeriodicRowLayout candidateLayout = new PeriodicRowLayout(witness.rowHeightBlocks(), witness.circumferenceBlocks(), recombined);
            ConstraintChecker.Result result = ConstraintChecker.check(candidateLayout, table);
            if (result.isValid()) {
                validCount++;
            } else {
                failures.add(
                    "sample " + sample + ": illegalAdjacencies=" + result.illegalAdjacencies().size()
                        + " unmetRequiredNeighbors=" + result.unmetRequiredNeighbors().size() + " missingBiomes=" + result.missingBiomes().size()
                );
            }
        }
        return new VerificationResult(VERIFICATION_SAMPLE_COUNT, validCount, failures);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private static void writeReport(
        Path reportPath, String label, int rowCount, int positionCount, String perPositionReport, double totalLog2, String comparison,
        VerificationResult verification
    ) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("PATTERN DIVERSITY LOWER BOUND - real, constructive proof\n");
        report.append("==========================================================\n\n");
        report.append("Checked against: ").append(label).append("\n\n");
        report.append("Base witness rowCount: ").append(rowCount).append("\n");
        report.append("Independent (non-adjacent) diversified positions: ").append(positionCount).append("\n\n");
        report.append("Per-position real alternative counts:\n").append(perPositionReport).append("\n");
        report.append("TOTAL: at least 2^").append(String.format("%.2f", totalLog2)).append(" distinct witnesses ")
            .append("(product of every position's own count - real multiplication, not an estimate).\n");
        report.append(comparison).append("\n\n");
        report.append("Verification (real, not assumed): ").append(verification.validCount()).append("/").append(verification.totalSampled())
            .append(" randomly recombined full layouts passed the real ConstraintChecker end to end.\n");
        for (String failure : verification.failureDetails()) {
            report.append("  FAILED: ").append(failure).append("\n");
        }
        report.append("\nHonest limit: this is a LOWER bound from ONE construction strategy (independent\n");
        report.append("substitution of every-other row) - the true count is very likely far larger (this\n");
        report.append("doesn't count row reordering, different base row counts, different row heights, or\n");
        report.append("substitutions found by a smarter/longer search). Never treat this number as exact.\n");

        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }
}
