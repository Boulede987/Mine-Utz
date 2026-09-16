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
    private static final int MIN_CELLS_PER_ROW = 6;
    private static final int MAX_CELLS_PER_ROW = 16;
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
        double rowHeightBlocks = defaultRowHeightBlocks(table);
        System.out.println(
            "Loaded " + table.allBiomeIds().size() + " biomes. rowHeightBlocks=" + rowHeightBlocks + " (independent of any live-algorithm constant)."
        );

        Random random = new Random(RANDOM_SEED);
        int totalAttempts = 0;
        for (int rowCount : ROW_COUNTS_TO_TRY) {
            long rowCountStartNanos = System.nanoTime();
            int[] rowsCompletedHistogram = new int[rowCount + 1];
            int checkerRejections = 0;
            for (int attempt = 0; attempt < ATTEMPTS_PER_ROW_COUNT; attempt++) {
                totalAttempts++;
                AttemptResult attemptResult = attemptGrid(table, rowCount, MIN_CELLS_PER_ROW, MAX_CELLS_PER_ROW, rowHeightBlocks, random);
                rowsCompletedHistogram[attemptResult.rowsCompleted()]++;
                if (attemptResult.layout() == null) {
                    continue;
                }
                ConstraintChecker.Result result = ConstraintChecker.check(attemptResult.layout(), table);
                if (result.isValid()) {
                    writeSuccessReport(attemptResult.layout(), table, totalAttempts, rowCount, reportPath, label);
                    System.out.println("PROOF FOUND after " + totalAttempts + " attempts (rowCount=" + rowCount + ").");
                    System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
                    return;
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
                "rowCount=" + rowCount + ": no success after " + ATTEMPTS_PER_ROW_COUNT + " attempts (" + String.format("%.1f", elapsedSeconds)
                    + "s, " + String.format("%.3f", elapsedSeconds / ATTEMPTS_PER_ROW_COUNT) + "s/attempt). Rows-completed histogram "
                    + Arrays.toString(rowsCompletedHistogram) + " (index = how many rows got placed before construction gave up; "
                    + rowCount + " means construction finished but ConstraintChecker rejected it). ConstraintChecker rejections: "
                    + checkerRejections + ". Trying a larger rowCount..."
            );
        }
        writeInconclusiveReport(totalAttempts, table, reportPath, label);
        System.out.println(
            "INCONCLUSIVE after " + totalAttempts + " total attempts across rowCount " + Arrays.toString(ROW_COUNTS_TO_TRY)
                + " - NOT a proof of impossibility, only this bounded search found nothing."
        );
        System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
    }

    private static double defaultRowHeightBlocks(RawRuleTable table) {
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

            PeriodicRowLayout.Row builtRow = null;
            for (int attempt = 0; attempt < ROW_BUILD_RETRIES && builtRow == null; attempt++) {
                int targetCellCount = minCellsPerRow + random.nextInt(maxCellsPerRow - minCellsPerRow + 1);
                List<BiomeId> sequence = buildRowSequence(table, targetCellCount, globallyUsed, random, previousRow, wraparoundRow, rowHeightBlocks);
                if (sequence == null) {
                    continue;
                }
                RowFeasibility.WidthInterval interval = RowFeasibility.achievableInterval(sequence, table, rowHeightBlocks);
                // The first row picks the shared circumference every later row must then fit -
                // arbitrary but principled: whatever the first successfully-built row needs.
                double candidateCircumference = circumferenceBlocks != null ? circumferenceBlocks : (interval.lowBlocks() + interval.highBlocks()) / 2.0;
                if (!RowFeasibility.fits(candidateCircumference, interval)) {
                    continue;
                }
                List<PeriodicRowLayout.Cell> cells = RowFeasibility.distributeWidths(sequence, table, rowHeightBlocks, candidateCircumference);
                PeriodicRowLayout.Row candidateRow = new PeriodicRowLayout.Row(cells);
                if (previousRow != null && !isVerticallyLegal(previousRow, candidateRow, table)) {
                    continue;
                }
                if (wraparoundRow != null && !isVerticallyLegal(candidateRow, wraparoundRow, table)) {
                    continue;
                }
                builtRow = candidateRow;
                circumferenceBlocks = candidateCircumference;
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

    private static Set<BiomeId> unionOfBiomeIds(List<PeriodicRowLayout.Row> rows) {
        Set<BiomeId> ids = new HashSet<>();
        for (PeriodicRowLayout.Row row : rows) {
            for (PeriodicRowLayout.Cell cell : row.cells()) {
                ids.add(cell.biomeId());
            }
        }
        return ids;
    }

    private static boolean isVerticallyLegal(PeriodicRowLayout.Row lower, PeriodicRowLayout.Row upper, RawRuleTable table) {
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
     * DURING the walk, not after, is what actually makes success reachable). Positions
     * used here are a provisional estimate (each biome's own midpoint width, not yet the
     * final exact-redistributed width from {@link RowFeasibility}) - good enough to guide
     * the walk; the caller re-verifies the FINAL row for real once distributed.
     *
     * <p><b>{@code globallyUsed} is read-only here</b> (real, measured bug: this method used
     * to add to it directly, which meant every DISCARDED candidate sequence from a failed
     * retry - and with {@code ROW_BUILD_RETRIES} in the hundreds, there are many - still
     * polluted the "already covered" set, so the coverage bias thought biomes were placed
     * that never actually made it into the grid; real 66-biome runs plateaued around
     * 15-20 covered no matter how much retry budget was thrown at it). The caller now only
     * commits a row's ids to the real set once that row is actually accepted.
     *
     * @return {@code null} if the walk hit a dead end (no legal next step) - caller retries with a fresh walk.
     */
    private static List<BiomeId> buildRowSequence(
        RawRuleTable table, int targetCellCount, Set<BiomeId> globallyUsed, Random random,
        PeriodicRowLayout.Row previousRow, PeriodicRowLayout.Row wraparoundRow, double rowHeightBlocks
    ) {
        List<BiomeId> allIds = table.allBiomeIds();
        // The row's OWN starting cell must also prefer unused biomes, not just its
        // interior steps - real, measured bug: leaving this uniform-random over all 66
        // left coverage stuck around 20-45/66 even with a large retry budget, since the
        // single biggest lever for reaching NEW territory (where a row even starts) was
        // ignoring coverage entirely.
        List<BiomeId> firstCandidates = allIds.stream()
            .filter(id -> previousRow == null || fitsVertically(id, 0, midpointWidth(id, table, rowHeightBlocks), previousRow, table))
            .filter(id -> wraparoundRow == null || fitsVertically(id, 0, midpointWidth(id, table, rowHeightBlocks), wraparoundRow, table))
            .toList();
        if (firstCandidates.isEmpty()) {
            return null;
        }
        // Local to this one row/attempt, separate from globallyUsed (read-only here, see
        // this method's own doc) - without it, nothing stops the walk from placing the
        // same biome twice within a single row, since globallyUsed no longer updates
        // mid-walk. Not a correctness issue on its own (immediate repeats are already
        // impossible - isLegal(X,X) is always false), just wasted coverage opportunity.
        Set<BiomeId> usedInThisRow = new HashSet<>();
        BiomeId first = pickPreferringUnused(firstCandidates, globallyUsed, usedInThisRow, random);
        usedInThisRow.add(first);
        List<BiomeId> sequence = new ArrayList<>();
        sequence.add(first);
        double provisionalCursor = midpointWidth(first, table, rowHeightBlocks);
        for (int i = 1; i < targetCellCount; i++) {
            BiomeId previous = sequence.get(sequence.size() - 1);
            boolean isClosingCell = i == targetCellCount - 1;
            List<BiomeId> candidates = legalCandidatesAfter(previous, table);
            if (isClosingCell) {
                candidates = candidates.stream().filter(id -> table.isLegal(id, first)).toList();
            }
            if (previousRow != null || wraparoundRow != null) {
                double cursor = provisionalCursor;
                candidates = candidates.stream()
                    .filter(id -> previousRow == null || fitsVertically(id, cursor, midpointWidth(id, table, rowHeightBlocks), previousRow, table))
                    .filter(id -> wraparoundRow == null || fitsVertically(id, cursor, midpointWidth(id, table, rowHeightBlocks), wraparoundRow, table))
                    .toList();
            }
            if (candidates.isEmpty()) {
                return null;
            }
            BiomeId chosen = pickPreferringUnused(candidates, globallyUsed, usedInThisRow, random);
            sequence.add(chosen);
            usedInThisRow.add(chosen);
            provisionalCursor += midpointWidth(chosen, table, rowHeightBlocks);
        }
        return sequence;
    }

    private static double midpointWidth(BiomeId id, RawRuleTable table, double rowHeightBlocks) {
        RawRuleTable.SizeRange range = table.sizeRangeOf(id);
        return ((range.minWidthBlocks() + range.maxWidthBlocks()) / 2.0) / rowHeightBlocks;
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
