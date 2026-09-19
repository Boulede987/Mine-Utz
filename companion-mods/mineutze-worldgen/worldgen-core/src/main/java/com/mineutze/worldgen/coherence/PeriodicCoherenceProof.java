package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The actual existence proof search - see this package's own doc for the technique
 * (periodic/toroidal reduction) and why this substrate (brick-wall rows, not the live hex
 * mesh). A SUCCESS is a complete, definitive proof, verified by {@link ConstraintChecker}
 * against the real constructed geometry - it cannot be a false positive. An INCONCLUSIVE
 * result after exhausting the search budget is NOT a proof of impossibility, and this
 * class says so, loudly, in its own report - never silently implies more than it found.
 *
 * <p>Construction strategy (v1, honestly a first cut, not claimed optimal): random walk
 * per row on the legal-adjacency graph, closing the loop back to the row's own first cell,
 * biased to prefer globally-not-yet-used biomes (drives toward full coverage), vertically
 * checked against BOTH the previous row and (for the last row) row 0's own wraparound seam
 * DURING the walk, not just after - real, measured need, see {@link #buildRowSequence}'s
 * own doc for why each of these was added. Real row-level BACKTRACKING (pop the last row
 * and re-roll it, not restart the whole grid) on top - per-row success chances compound
 * multiplicatively, so a flat restart made anything past ~6 rows unreachable. Row widths
 * are computed EXACTLY (see {@link RowFeasibility}), never approximated.
 *
 * <p>Real result against the current 66-biome catalog (2026-09-16): SUCCEEDS at
 * rowCount=65 (715 real cells), reproducibly, in ~145 attempts. Tuning history worth
 * knowing if this ever needs revisiting: longer individual rows (tried up to 35 cells)
 * made things WORSE, not better - per-cell dead-end risk compounds within a row too, so
 * many SHORT rows beats few LONG ones for total capacity.
 */
public final class PeriodicCoherenceProof {

    // Fixed, not System.currentTimeMillis() - a SUCCESS must be reproducible: re-running
    // this exact seed against an unchanged biome-graph.json must find the same certificate.
    private static final long RANDOM_SEED = 20260916L;
    private static final int[] ROW_COUNTS_TO_TRY = {65, 80, 100};
    // Package-visible (not private): PatternDiversityLowerBound reuses these same bounds
    // when constructing alternative rows via tryBuildLegalRow, rather than a second,
    // independently-maintained copy that could silently drift from the real search's own.
    static final int MIN_CELLS_PER_ROW = 6;
    static final int MAX_CELLS_PER_ROW = 16;
    private static final int ROW_BUILD_RETRIES = 100;
    private static final int ATTEMPTS_PER_ROW_COUNT = 200;
    private static final Path REPORT_PATH = Path.of("doc", "graph-coherence-existence-proof-report.txt");

    private PeriodicCoherenceProof() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        runProof(RawRuleTable.from(data), REPORT_PATH, "production ModBiomeGraph.OVERWORLD (src/main/resources/biomegraph/biome-graph.json)");
    }

    /**
     * The real search entry point - {@link #main} is a thin wrapper around this for the
     * production catalog; {@code GraphEditorCoherenceCheck} (tooling.graphedit) calls this
     * directly against the graph editor's own currently-SAVED data (not yet rebuilt into a
     * classpath resource), same relationship {@code GeometricFeasibilityProof.runProof} and
     * {@code GraphEditorDeepCheck} already have.
     *
     * @param label human-readable description of what was checked, written into the report so a reader knows without guessing.
     */
    public static void runProof(RawRuleTable table, Path reportPath, String label) throws IOException {
        WitnessSearchResult searchResult = findWitness(table);
        if (searchResult.layout() != null) {
            writeSuccessReport(searchResult.layout(), table, searchResult.totalAttempts(), searchResult.layout().rows().size(), reportPath, label);
            System.out.println("PROOF FOUND after " + searchResult.totalAttempts() + " attempts (rowCount=" + searchResult.layout().rows().size() + ").");
            System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
            return;
        }
        writeInconclusiveReport(searchResult.totalAttempts(), table, reportPath, label);
        System.out.println(
            "INCONCLUSIVE after " + searchResult.totalAttempts() + " total attempts across rowCount " + Arrays.toString(ROW_COUNTS_TO_TRY)
                + " - NOT a proof of impossibility, only this bounded search found nothing."
        );
        System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
    }

    /** {@code layout} is {@code null} on an inconclusive search - {@code totalAttempts} is kept either way, for reporting. */
    public record WitnessSearchResult(PeriodicRowLayout layout, int totalAttempts) {
    }

    /**
     * The search itself, with no report-writing side effect - {@link #runProof} wraps this
     * for the production/editor entry points; {@code EscapeHatchExtendabilityProof} calls
     * this directly to get the actual witness object it needs to check row-to-row
     * transitions against, not just a human-readable report of one.
     */
    public static WitnessSearchResult findWitness(RawRuleTable table) {
        return findWitness(table, RANDOM_SEED);
    }

    /**
     * Same search as {@link #findWitness(RawRuleTable)}, but driven by an explicit random
     * seed instead of the fixed internal {@link #RANDOM_SEED} constant - different seeds
     * deterministically produce different witnesses (real basis for {@code
     * SeedDrivenPatchGenerator}'s own per-worldSeed pattern generation, 2026-09-17 user
     * direction). The fixed-seed overload above stays reproducible for the production
     * report/existing callers; this is the one real point of variation.
     */
    public static WitnessSearchResult findWitness(RawRuleTable table, long randomSeed) {
        return findWitness(table, randomSeed, MIN_CELLS_PER_ROW, MAX_CELLS_PER_ROW, ROW_COUNTS_TO_TRY, ATTEMPTS_PER_ROW_COUNT);
    }

    /**
     * Fully parameterized search - the two overloads above delegate here using the
     * production defaults. Real basis for the periodic-patch WIDTH-scaling question
     * (2026-09-17): {@code minCellsPerRow}/{@code maxCellsPerRow} are normally fixed
     * production constants, but circumference (the row's own wrap distance, real "width" of
     * the patch) is a direct, mechanical function of how many cells a row can hold - this
     * lets a caller (e.g. a width-scaling experiment) drive that directly without touching
     * the production defaults every other caller relies on.
     */
    public static WitnessSearchResult findWitness(
        RawRuleTable table, long randomSeed, int minCellsPerRow, int maxCellsPerRow, int[] rowCountsToTry, int attemptsPerRowCount
    ) {
        double rowHeightBlocks = defaultRowHeightBlocks(table);
        System.out.println(
            "Loaded " + table.allBiomeIds().size() + " biomes. rowHeightBlocks=" + rowHeightBlocks + " (independent of any live-algorithm constant)."
        );

        Random random = new Random(randomSeed);
        int totalAttempts = 0;
        for (int rowCount : rowCountsToTry) {
            long rowCountStartNanos = System.nanoTime();
            int[] rowsCompletedHistogram = new int[rowCount + 1];
            int checkerRejections = 0;
            for (int attempt = 0; attempt < attemptsPerRowCount; attempt++) {
                totalAttempts++;
                AttemptResult attemptResult = attemptGrid(table, rowCount, minCellsPerRow, maxCellsPerRow, rowHeightBlocks, random);
                rowsCompletedHistogram[attemptResult.rowsCompleted()]++;
                if (attemptResult.layout() == null) {
                    continue;
                }
                ConstraintChecker.Result result = ConstraintChecker.check(attemptResult.layout(), table);
                if (result.isValid()) {
                    System.out.println("PROOF FOUND after " + totalAttempts + " attempts (rowCount=" + rowCount + ").");
                    return new WitnessSearchResult(attemptResult.layout(), totalAttempts);
                }
                checkerRejections++;
                if (checkerRejections <= 3) {
                    System.out.println(
                        "  rejection sample: illegalAdjacencies=" + result.illegalAdjacencies().size()
                            + " unmetRequiredNeighbors=" + result.unmetRequiredNeighbors().size()
                            + " missingBiomes=" + result.missingBiomes().size() + "/" + table.allBiomeIds().size()
                    );
                }
            }
            double elapsedSeconds = (System.nanoTime() - rowCountStartNanos) / 1_000_000_000.0;
            System.out.println(
                "rowCount=" + rowCount + ": no success after " + attemptsPerRowCount + " attempts (" + String.format("%.1f", elapsedSeconds)
                    + "s, " + String.format("%.3f", elapsedSeconds / attemptsPerRowCount) + "s/attempt). Rows-completed histogram "
                    + Arrays.toString(rowsCompletedHistogram) + " (index = how many rows got placed before construction gave up; "
                    + rowCount + " means construction finished but ConstraintChecker rejected it). ConstraintChecker rejections: "
                    + checkerRejections + ". Trying a larger rowCount..."
            );
        }
        return new WitnessSearchResult(null, totalAttempts);
    }

    /** Package-visible: {@code FailureModeCharacterization} reuses this same default for any custom table it builds, rather than a second copy. */
    static double defaultRowHeightBlocks(RawRuleTable table) {
        int minOfMins = table.allBiomeIds().stream().mapToInt(id -> table.sizeRangeOf(id).minWidthBlocks()).min().orElseThrow();
        return Math.max(10.0, minOfMins / 4.0);
    }

    /** {@code rowsCompleted}: on failure, the deepest row count this attempt ever reached (diagnostic only, not used for correctness). */
    private record AttemptResult(PeriodicRowLayout layout, int rowsCompleted) {
    }

    // Real, measured need: without backtracking, per-row success chances compound
    // multiplicatively (even a comfortable 70% per row means 0.7^20 =~ 0.08% for a
    // 20-row grid) - a flat restart-the-whole-attempt-on-any-failure strategy made full
    // completion of anything beyond ~6 rows essentially unreachable. This caps how many
    // times ANY row may be backtracked-into across one whole attempt, so a genuinely
    // pathological random trajectory still terminates instead of looping indefinitely.
    private static final int MAX_BACKTRACKS_PER_ATTEMPT = 300;

    /**
     * @return a fully positioned, geometrically exact layout, or {@code null} if this
     * whole attempt exhausted its backtrack budget. Rows are built one at a time via
     * {@link #buildRowSequence} (which is itself vertically-aware against the previous row,
     * and against row 0 for the last row); when a row can't be built within {@link
     * #ROW_BUILD_RETRIES} tries, the PREVIOUS row is popped and re-rolled (see {@link
     * #MAX_BACKTRACKS_PER_ATTEMPT}'s own doc for why this matters) rather than discarding
     * this whole attempt's progress. {@link ConstraintChecker} remains the sole authority
     * on correctness regardless of how this search behaves - everything here only affects
     * whether a valid layout gets FOUND, never whether an accepted one is actually valid.
     */
    private static AttemptResult attemptGrid(
        RawRuleTable table, int rowCount, int minCellsPerRow, int maxCellsPerRow, double rowHeightBlocks, Random random
    ) {
        List<PeriodicRowLayout.Row> rows = new ArrayList<>();
        Double circumferenceBlocks = null;
        int backtracksUsed = 0;
        int deepestReached = 0;

        while (rows.size() < rowCount) {
            deepestReached = Math.max(deepestReached, rows.size());
            PeriodicRowLayout.Row previousRow = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            // Recomputed from the currently-standing rows, not incrementally tracked - a
            // backtrack (popping a row) must also "forget" that row's own contribution,
            // and rebuilding fresh here is simpler and safer than trying to undo a Set
            // mutation correctly when another still-standing row might share the same id.
            Set<BiomeId> globallyUsed = unionOfBiomeIds(rows);

            // The LAST row must also be vertically legal against row 0 (the wraparound
            // seam) - real, measured need: without construction-time awareness of this,
            // the walk had zero guidance for it, and every attempt that got this deep
            // stalled exactly one row short, every time, only ever discovering the
            // wraparound conflict AFTER fully building the row (see the post-hoc check
            // below), by which point backtracking had nothing better to try.
            boolean isLastRow = rows.size() == rowCount - 1;
            PeriodicRowLayout.Row wraparoundRow = isLastRow && rowCount > 1 ? rows.get(0) : null;

            PeriodicRowLayout.Row builtRow;
            if (circumferenceBlocks == null) {
                // Row 0 only: no fixed circumference yet - the first successfully-built
                // row's own achievable interval picks it (see #findWitness's own doc), so
                // this one case can't go through the fixed-circumference helper below.
                builtRow = tryBuildAnchorRow(table, globallyUsed, minCellsPerRow, maxCellsPerRow, rowHeightBlocks, random, ROW_BUILD_RETRIES);
                if (builtRow != null) {
                    circumferenceBlocks = builtRow.totalWidthBlocks();
                }
            } else {
                builtRow = tryBuildLegalRow(
                    table, globallyUsed, previousRow, wraparoundRow, minCellsPerRow, maxCellsPerRow, rowHeightBlocks, circumferenceBlocks, random,
                    ROW_BUILD_RETRIES
                );
            }

            if (builtRow != null) {
                rows.add(builtRow);
                continue;
            }
            // Backtrack: pop the previous row (if any) and let it re-roll, instead of
            // discarding this whole attempt's progress.
            if (rows.isEmpty() || backtracksUsed >= MAX_BACKTRACKS_PER_ATTEMPT) {
                return new AttemptResult(null, deepestReached);
            }
            rows.remove(rows.size() - 1);
            if (rows.isEmpty()) {
                circumferenceBlocks = null; // row 0 itself was popped - a fresh one may need a different circumference
            }
            backtracksUsed++;
        }

        if (!isVerticallyLegal(rows.get(rows.size() - 1), rows.get(0), table)) {
            return new AttemptResult(null, rowCount - 1); // wraparound pair itself failed - report as "one short"
        }
        return new AttemptResult(new PeriodicRowLayout(rowHeightBlocks, circumferenceBlocks, rows), rowCount);
    }

    /**
     * Tries up to {@code retries} times to build ONE legal row at an ALREADY-FIXED
     * circumference (unlike row 0's own once-per-attempt circumference selection inside
     * {@link #attemptGrid}, which this does not handle) - package-visible so {@code
     * ReachabilityScoutingChecks} can reuse the exact same row-building mechanism to probe
     * "does this specific previous row have ANY legal successor at all," not a separate
     * copy of this logic.
     *
     * @return a legal row, or {@code null} if no legal row was found within {@code retries} attempts.
     */
    static PeriodicRowLayout.Row tryBuildLegalRow(
        RawRuleTable table, Set<BiomeId> globallyUsed, PeriodicRowLayout.Row previousRow, PeriodicRowLayout.Row wraparoundRow,
        int minCellsPerRow, int maxCellsPerRow, double rowHeightBlocks, double circumferenceBlocks, Random random, int retries
    ) {
        for (int attempt = 0; attempt < retries; attempt++) {
            int targetCellCount = minCellsPerRow + random.nextInt(maxCellsPerRow - minCellsPerRow + 1);
            List<BiomeId> sequence = buildRowSequence(
                table, targetCellCount, globallyUsed, random, previousRow, wraparoundRow, rowHeightBlocks, circumferenceBlocks
            );
            if (sequence == null) {
                continue;
            }
            RowFeasibility.WidthInterval interval = RowFeasibility.achievableInterval(sequence, table, rowHeightBlocks);
            if (!RowFeasibility.fits(circumferenceBlocks, interval)) {
                continue;
            }
            List<PeriodicRowLayout.Cell> cells = RowFeasibility.distributeWidths(sequence, table, rowHeightBlocks, circumferenceBlocks);
            PeriodicRowLayout.Row candidateRow = new PeriodicRowLayout.Row(cells);
            if (previousRow != null && !isVerticallyLegal(previousRow, candidateRow, table)) {
                continue;
            }
            if (wraparoundRow != null && !isVerticallyLegal(candidateRow, wraparoundRow, table)) {
                continue;
            }
            return candidateRow;
        }
        return null;
    }

    /**
     * Builds ONE legal row with no vertical constraint at all (no previousRow, no
     * wraparoundRow) and no target circumference to match - its own achievable interval's
     * midpoint BECOMES the circumference (the row's own {@link
     * PeriodicRowLayout.Row#totalWidthBlocks} is that circumference, by construction).
     * Package-visible: {@code FailureModeCharacterization} reuses this to establish its own
     * real, self-consistent circumference to sample against, without needing a full torus
     * witness first (which a deliberately-broken graph may never produce).
     *
     * @return a legal row whose own total width can be used directly as a circumference, or {@code null} if none was found within {@code retries} attempts.
     */
    static PeriodicRowLayout.Row tryBuildAnchorRow(
        RawRuleTable table, Set<BiomeId> globallyUsed, int minCellsPerRow, int maxCellsPerRow, double rowHeightBlocks, Random random, int retries
    ) {
        for (int attempt = 0; attempt < retries; attempt++) {
            int targetCellCount = minCellsPerRow + random.nextInt(maxCellsPerRow - minCellsPerRow + 1);
            // circumferenceBlocks is unused when both previousRow/wraparoundRow are null
            // (no vertical constraint yet) - 0.0 is a safe placeholder.
            List<BiomeId> sequence = buildRowSequence(table, targetCellCount, globallyUsed, random, null, null, rowHeightBlocks, 0.0);
            if (sequence == null) {
                continue;
            }
            RowFeasibility.WidthInterval interval = RowFeasibility.achievableInterval(sequence, table, rowHeightBlocks);
            double candidateCircumference = (interval.lowBlocks() + interval.highBlocks()) / 2.0;
            List<PeriodicRowLayout.Cell> cells = RowFeasibility.distributeWidths(sequence, table, rowHeightBlocks, candidateCircumference);
            return new PeriodicRowLayout.Row(cells);
        }
        return null;
    }

    private static Set<BiomeId> unionOfBiomeIds(List<PeriodicRowLayout.Row> rows) {
        Set<BiomeId> ids = new HashSet<>();
        for (PeriodicRowLayout.Row row : rows) {
            for (PeriodicRowLayout.Cell cell : row.cells()) {
                ids.add(cell.biomeId());
            }
        }
        return ids;
    }

    /** Package-visible: {@code EscapeHatchExtendabilityProof} reuses this exact same check to independently re-verify each witness row-to-row transition, rather than duplicating the pairwise overlap+legality logic. */
    static boolean isVerticallyLegal(PeriodicRowLayout.Row lower, PeriodicRowLayout.Row upper, RawRuleTable table) {
        for (PeriodicRowLayout.Cell lowerCell : lower.cells()) {
            for (PeriodicRowLayout.Cell upperCell : upper.cells()) {
                if (AdjacencyDerivation.overlaps(lowerCell, upperCell) && !table.isLegal(lowerCell.biomeId(), upperCell.biomeId())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Random walk on the legal-adjacency graph, {@code targetCellCount} steps, closing back
     * to its own first cell (the last cell must be legal to both its predecessor AND the
     * row's first cell, since the row wraps horizontally). Biased to prefer a globally-
     * not-yet-used biome at every step when one is legally available, driving toward full
     * catalog coverage without guaranteeing it - {@link ConstraintChecker} is the real check.
     *
     * <p><b>Vertically-aware when {@code previousRow != null}</b> (real, measured need: a
     * purely horizontal walk, checked against the previous row only AFTER being fully
     * built, succeeded in roughly 1 attempt per 2000 against the real ~20% legal-pair
     * density - checking every candidate's PROVISIONAL position against the previous row
     * DURING the walk, not after, is what actually makes success reachable).
     *
     * <p><b>FIXED (2026-09), real bug found while building {@code
     * ReachabilityScoutingChecks}:</b> positions here used to be each biome's own MIDPOINT
     * width, independent of the actual target circumference - a 3-biome synthetic fixture
     * where all 3 shared the exact same declared range (so every cell's real final width,
     * 6.25, was identical and notably BELOW the naive midpoint, 6.5) caused a hand-verified-
     * legal successor to be UNREACHABLE by this walk even at 100,000 retries, not just
     * statistically rare - the systematic overestimate compounded cell-by-cell, eventually
     * making a candidate "see" a false extra overlap it would never actually reach post-
     * redistribution. Root cause: this method never knew the target circumference at all.
     * Fixed by estimating each candidate's width from the SAME proportional-fraction formula
     * {@link RowFeasibility} uses for the real final widths, not an independent per-biome
     * guess - the fraction is estimated from cells already committed (exact) plus the
     * remaining not-yet-chosen cells (approximated via the whole catalog's own average
     * low/high), so it CONVERGES to the exact fraction as the row fills in, and is already
     * exact from the first cell in the degenerate case that broke the old approach (uniform
     * ranges make the catalog average equal every individual biome's own range). See {@link
     * #fitsWithEstimate}. This was always a completeness/reliability gap, never a soundness
     * one - {@link ConstraintChecker}/{@link #isVerticallyLegal} were always the real
     * authority on anything actually accepted; this only affects whether a valid row gets
     * FOUND, same as everywhere else in this search.
     *
     * <p><b>{@code globallyUsed} is read-only here</b> (real, measured bug: this method used
     * to add to it directly, which meant every DISCARDED candidate sequence from a failed
     * retry - and with {@code ROW_BUILD_RETRIES} in the hundreds, there are many - still
     * polluted the "already covered" set, so the coverage bias thought biomes were placed
     * that never actually made it into the grid; real 66-biome runs plateaued around
     * 15-20 covered no matter how much retry budget was thrown at it). The caller now only
     * commits a row's ids to the real set once that row is actually accepted.
     *
     * <p><b>Real CELL-level backtracking (2026-09-17)</b> - direct fix for a real, explained
     * asymmetry: row-STACKING (vertical growth) already backtracks at the row level (pop the
     * last row, re-roll it - see {@link #attemptGrid}), so an occasional dead end costs one
     * row's progress, not the whole attempt. Building the cells WITHIN one row (horizontal
     * growth) used to have no such recovery - any dead end anywhere in the walk discarded
     * the ENTIRE partial sequence, so per-cell failure probability compounded with zero
     * recovery, making long rows disproportionately fragile (real, measured: rows past ~16
     * cells got WORSE, not better, before this fix - see this class's own top-level doc).
     * Now a dead end at position {@code i} pops position {@code i-1}'s own choice and
     * excludes it from being immediately re-picked there, exactly mirroring
     * {@link #attemptGrid}'s own row-level strategy one level down - bounded by {@link
     * #cellBacktrackBudgetFor} so a genuinely pathological walk still terminates.
     *
     * <p><b>Budget SCALES with row length, not a flat constant (2026-09-17 real tuning):</b>
     * a flat 2000-backtrack budget measurably slowed the real 66-biome search down (9m7s for
     * 73 attempts at the current 16-cell row cap, vs sub-minute before this fix) - a
     * genuinely doomed SHORT row could burn most of that budget recovering before giving up,
     * instead of failing fast like the old flat-restart approach did. Scaling the budget to
     * {@code targetCellCount} keeps short rows cheap (few real backtrack points exist to
     * exploit anyway) while still giving long rows (the whole reason this fix exists) real
     * room to recover.
     *
     * <p><b>GATED OFF entirely for today's normal row lengths (2026-09-17, second real
     * tuning):</b> even the scaled-down budget above was measurably WORSE than a flat
     * restart for {@code targetCellCount <= MAX_CELLS_PER_ROW} (19m30s, one real trial) -
     * this mechanism's whole reason for existing is rows MUCH longer than today's 16-cell
     * cap (the periodic-patch width-scaling question, see this class's own git history
     * 2026-09-17), where a full restart is expensive and recovery genuinely pays off. Below
     * that length, a restart is already cheap, so backtracking is pure overhead with nothing
     * to offset it. {@link #isCellBacktrackingWorthwhile} keeps today's default search
     * byte-behavior-identical to before this whole mechanism existed (first dead end fails
     * immediately, same as the original flat-restart {@code buildRowSequence}) - only rows
     * past the normal cap ever pay the backtracking cost, and only they get its benefit.
     *
     * @return {@code null} if the walk exhausted its cell-backtrack budget - caller retries with a fresh walk.
     */
    private static int cellBacktrackBudgetFor(int targetCellCount) {
        return Math.max(50, targetCellCount * 15);
    }

    private static boolean isCellBacktrackingWorthwhile(int targetCellCount) {
        return targetCellCount > MAX_CELLS_PER_ROW;
    }

    private static List<BiomeId> buildRowSequence(
        RawRuleTable table, int targetCellCount, Set<BiomeId> globallyUsed, Random random,
        PeriodicRowLayout.Row previousRow, PeriodicRowLayout.Row wraparoundRow, double rowHeightBlocks, double circumferenceBlocks
    ) {
        List<BiomeId> allIds = table.allBiomeIds();
        boolean verticallyConstrained = previousRow != null || wraparoundRow != null;
        // Computed once per row attempt, not per candidate - real per-biome ranges don't
        // change mid-walk, only which ones are already committed does.
        CatalogAverageWidths averages = verticallyConstrained ? catalogAverageWidths(table, rowHeightBlocks) : null;
        int cellBacktrackBudget = cellBacktrackBudgetFor(targetCellCount);

        List<BiomeId> sequence = new ArrayList<>();
        // excludedAtPosition.get(i) = biomes already tried-and-failed AT position i, for
        // THIS position's current predecessor context - cleared whenever backtracking past
        // position i changes what that predecessor context even is (a fresh slate, real
        // stale-exclusion bug avoided, not just "always append, never reset").
        List<Set<BiomeId>> excludedAtPosition = new ArrayList<>();
        int cellBacktracksUsed = 0;
        boolean backtrackingWorthwhile = isCellBacktrackingWorthwhile(targetCellCount);

        while (sequence.size() < targetCellCount) {
            int i = sequence.size();
            ensureExclusionSlot(excludedAtPosition, i);
            List<BiomeId> candidates = candidatesAtPosition(
                i, sequence, allIds, table, targetCellCount, verticallyConstrained, rowHeightBlocks, circumferenceBlocks, averages, previousRow,
                wraparoundRow
            );
            candidates = candidates.stream().filter(id -> !excludedAtPosition.get(i).contains(id)).toList();

            if (candidates.isEmpty()) {
                if (i == 0 || !backtrackingWorthwhile || cellBacktracksUsed >= cellBacktrackBudget) {
                    return null;
                }
                excludedAtPosition.get(i).clear();
                BiomeId poppedValue = sequence.remove(sequence.size() - 1);
                ensureExclusionSlot(excludedAtPosition, i - 1);
                excludedAtPosition.get(i - 1).add(poppedValue);
                cellBacktracksUsed++;
                continue;
            }

            // Recomputed fresh each step, not incrementally maintained through backtracking
            // - simpler and correctness-safe (a biome CAN legitimately repeat non-
            // consecutively within a row, so naively "un-marking" a popped value would be
            // wrong if it still appears elsewhere in the standing sequence).
            Set<BiomeId> usedInThisRow = new HashSet<>(sequence);
            BiomeId chosen = pickPreferringUnused(candidates, globallyUsed, usedInThisRow, random);
            sequence.add(chosen);
        }
        return sequence;
    }

    private static void ensureExclusionSlot(List<Set<BiomeId>> excludedAtPosition, int index) {
        while (excludedAtPosition.size() <= index) {
            excludedAtPosition.add(new HashSet<>());
        }
    }

    /**
     * Real legal candidates at position {@code i} given {@code sequence}'s own current
     * (possibly backtracked) state - same branching {@code buildRowSequence} always had
     * (first cell prefers coverage/fits the vertical estimate; interior cells must be legal
     * after their predecessor, the CLOSING cell must ALSO be legal back to the first cell,
     * and every cell is vertically-estimate-checked when constrained), just extracted so it
     * can be called fresh at any position, including one being RE-tried after a backtrack.
     */
    private static List<BiomeId> candidatesAtPosition(
        int i, List<BiomeId> sequence, List<BiomeId> allIds, RawRuleTable table, int targetCellCount, boolean verticallyConstrained,
        double rowHeightBlocks, double circumferenceBlocks, CatalogAverageWidths averages, PeriodicRowLayout.Row previousRow,
        PeriodicRowLayout.Row wraparoundRow
    ) {
        if (i == 0) {
            return allIds.stream()
                .filter(id -> !verticallyConstrained
                    || fitsWithEstimate(List.of(), id, targetCellCount, table, rowHeightBlocks, circumferenceBlocks, averages, previousRow, wraparoundRow))
                .toList();
        }
        BiomeId previous = sequence.get(i - 1);
        boolean isClosingCell = i == targetCellCount - 1;
        List<BiomeId> candidates = legalCandidatesAfter(previous, table);
        if (isClosingCell) {
            BiomeId first = sequence.get(0);
            candidates = candidates.stream().filter(id -> table.isLegal(id, first)).toList();
        }
        if (verticallyConstrained) {
            candidates = candidates.stream()
                .filter(id -> fitsWithEstimate(sequence, id, targetCellCount, table, rowHeightBlocks, circumferenceBlocks, averages, previousRow, wraparoundRow))
                .toList();
        }
        return candidates;
    }

    /** Catalog-wide average min/max width (in row-height units), used only to estimate the NOT-YET-CHOSEN portion of a row still being built - see {@link #fitsWithEstimate}. */
    private record CatalogAverageWidths(double averageMinWidth, double averageMaxWidth) {
    }

    private static CatalogAverageWidths catalogAverageWidths(RawRuleTable table, double rowHeightBlocks) {
        double totalMin = 0;
        double totalMax = 0;
        for (BiomeId id : table.allBiomeIds()) {
            RawRuleTable.SizeRange range = table.sizeRangeOf(id);
            totalMin += range.minWidthBlocks() / rowHeightBlocks;
            totalMax += range.maxWidthBlocks() / rowHeightBlocks;
        }
        int count = table.allBiomeIds().size();
        return new CatalogAverageWidths(totalMin / count, totalMax / count);
    }

    /**
     * Would placing {@code candidate} right after {@code committedSoFar} still be legal
     * against previousRow/wraparoundRow, using an ESTIMATED position? The estimate uses the
     * exact same proportional-fraction formula {@link RowFeasibility} uses for the real
     * final widths - {@code committedSoFar + candidate}'s own low/high sums are exact (their
     * real declared ranges), the still-unchosen remainder is approximated via the catalog's
     * own average low/high. This converges to the EXACT fraction as the row fills in (zero
     * remainder left), and is already exact from the very first cell whenever every
     * candidate biome shares the same range (the degenerate case that broke the old flat
     * midpoint estimate - see this class's own {@link #buildRowSequence} doc).
     */
    private static boolean fitsWithEstimate(
        List<BiomeId> committedSoFar, BiomeId candidate, int targetCellCount, RawRuleTable table, double rowHeightBlocks,
        double circumferenceBlocks, CatalogAverageWidths averages, PeriodicRowLayout.Row previousRow, PeriodicRowLayout.Row wraparoundRow
    ) {
        double committedMin = 0;
        double committedMax = 0;
        for (BiomeId id : committedSoFar) {
            RawRuleTable.SizeRange range = table.sizeRangeOf(id);
            committedMin += range.minWidthBlocks() / rowHeightBlocks;
            committedMax += range.maxWidthBlocks() / rowHeightBlocks;
        }
        RawRuleTable.SizeRange candidateRange = table.sizeRangeOf(candidate);
        double candidateMin = candidateRange.minWidthBlocks() / rowHeightBlocks;
        double candidateMax = candidateRange.maxWidthBlocks() / rowHeightBlocks;

        int remaining = targetCellCount - committedSoFar.size() - 1;
        double estimatedTotalMin = committedMin + candidateMin + remaining * averages.averageMinWidth();
        double estimatedTotalMax = committedMax + candidateMax + remaining * averages.averageMaxWidth();
        // Degenerate (zero estimated slack, e.g. every remaining candidate has an
        // identical min=max) - fall back to the midpoint fraction rather than divide by zero.
        double fraction = estimatedTotalMax <= estimatedTotalMin
            ? 0.5
            : Math.max(0, Math.min(1, (circumferenceBlocks - estimatedTotalMin) / (estimatedTotalMax - estimatedTotalMin)));

        double start = 0;
        for (BiomeId id : committedSoFar) {
            RawRuleTable.SizeRange range = table.sizeRangeOf(id);
            double min = range.minWidthBlocks() / rowHeightBlocks;
            double max = range.maxWidthBlocks() / rowHeightBlocks;
            start += min + fraction * (max - min);
        }
        double width = candidateMin + fraction * (candidateMax - candidateMin);

        boolean okPrevious = previousRow == null || fitsVertically(candidate, start, width, previousRow, table);
        boolean okWraparound = wraparoundRow == null || fitsVertically(candidate, start, width, wraparoundRow, table);
        return okPrevious && okWraparound;
    }

    private static boolean fitsVertically(BiomeId candidateId, double start, double width, PeriodicRowLayout.Row previousRow, RawRuleTable table) {
        for (PeriodicRowLayout.Cell prevCell : previousRow.cells()) {
            if (AdjacencyDerivation.intervalsOverlap(start, width, prevCell.startBlocks(), prevCell.widthBlocks())
                && !table.isLegal(candidateId, prevCell.biomeId())) {
                return false;
            }
        }
        return true;
    }

    private static List<BiomeId> legalCandidatesAfter(BiomeId previous, RawRuleTable table) {
        return table.allBiomeIds().stream().filter(id -> table.isLegal(previous, id)).toList();
    }

    private static BiomeId pickPreferringUnused(List<BiomeId> candidates, Set<BiomeId> globallyUsed, Set<BiomeId> usedInThisRow, Random random) {
        List<BiomeId> unused = candidates.stream().filter(id -> !globallyUsed.contains(id) && !usedInThisRow.contains(id)).toList();
        List<BiomeId> pool = unused.isEmpty() ? candidates : unused;
        return pool.get(random.nextInt(pool.size()));
    }

    private static void writeSuccessReport(
        PeriodicRowLayout layout, RawRuleTable table, int attemptsUsed, int rowCount, Path reportPath, String label
    ) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("GRAPH COHERENCE EXISTENCE PROOF - SUCCESS\n");
        report.append("==========================================\n\n");
        report.append("Checked against: ").append(label).append("\n\n");
        report.append("This is a COMPLETE, DEFINITIVE proof that the checked rule set admits an infinite\n");
        report.append("valid world: the periodic patch below satisfies every real constraint (adjacency\n");
        report.append("legality, required-neighbor minimums, full-catalog coverage) including across its\n");
        report.append("own wraparound seam - repeating it forever in every direction therefore produces\n");
        report.append("a valid infinite world.\n\n");
        report.append("This does NOT mean the LIVE generator (FaceIdentityCsp/HexMeshBuilder/etc.) can\n");
        report.append("find this or any other valid world - this proof is fully independent of that code,\n");
        report.append("by design (see com.mineutze.worldgen.coherence's own package doc).\n\n");
        report.append("rowCount=").append(rowCount).append(", rowHeightBlocks=").append(layout.rowHeightBlocks())
            .append(", circumferenceBlocks=").append(layout.circumferenceBlocks()).append("\n");
        report.append("Found after ").append(attemptsUsed).append(" randomized construction attempts (seed=").append(RANDOM_SEED)
            .append(", reproducible).\n\n");

        int rowIndex = 0;
        for (PeriodicRowLayout.Row row : layout.rows()) {
            report.append("Row ").append(rowIndex++).append(" (").append(row.cells().size()).append(" cells, total width ")
                .append(String.format("%.2f", row.totalWidthBlocks())).append("):\n");
            for (PeriodicRowLayout.Cell cell : row.cells()) {
                double area = cell.widthBlocks() * layout.rowHeightBlocks();
                report.append("  [").append(String.format("%.2f", cell.startBlocks())).append(" - ")
                    .append(String.format("%.2f", cell.startBlocks() + cell.widthBlocks())).append("] ")
                    .append(cell.biomeId()).append(" width=").append(String.format("%.2f", cell.widthBlocks()))
                    .append(" area=").append(String.format("%.2f", area))
                    .append(" (declared range ").append(table.sizeRangeOf(cell.biomeId())).append(")\n");
            }
        }

        report.append("\nAll ").append(table.allBiomeIds().size()).append(" declared biomes appear at least once. ")
            .append("Zero illegal adjacencies, zero unmet required-neighbor minimums (verified by ConstraintChecker.check).\n");

        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }

    private static void writeInconclusiveReport(int totalAttempts, RawRuleTable table, Path reportPath, String label) throws IOException {
        String report = "GRAPH COHERENCE EXISTENCE PROOF - INCONCLUSIVE\n"
            + "================================================\n\n"
            + "Checked against: " + label + "\n\n"
            + "No valid periodic patch found after " + totalAttempts + " randomized construction attempts\n"
            + "across rowCount " + Arrays.toString(ROW_COUNTS_TO_TRY) + " (seed=" + RANDOM_SEED + ").\n\n"
            + "THIS IS NOT A PROOF OF IMPOSSIBILITY. Only a bounded, randomized search was tried - a\n"
            + "real solution may still exist at a larger rowCount, wider per-row cell range, or via a\n"
            + "smarter (non-random) construction strategy. Do not treat this as \"the graph is broken.\"\n\n"
            + "Catalog size: " + table.allBiomeIds().size() + " biomes.\n";
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
    }
}
