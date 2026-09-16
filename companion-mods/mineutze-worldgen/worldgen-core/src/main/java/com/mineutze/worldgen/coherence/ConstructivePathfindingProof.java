package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.PeriodicCoherenceProof.WitnessSearchResult;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * "Option 1" from the reachability menu (2026-09) - deliberately NOT a universal
 * reachability proof, which is likely false in general given the same real dead-end
 * evidence that already killed the unrestricted extendability claim (see this package's
 * and {@link ReachabilityScoutingChecks}'s own docs). Instead: for a real sample of
 * starting rows, searches for a finite, explicit sequence of legal rows that reaches a
 * {@code SafeStates}-aligned row (see {@link EscapeHatchExtendabilityProof}). Each SUCCESS
 * is a real, replayable, individually-checkable certificate for that ONE starting row -
 * "if you are ever in exactly this state, here is the exact route to safety" - not a
 * sampled probability. Each FAILURE (no alignment found within the search budget) is only
 * ever inconclusive for that starting row, same honesty boundary as everywhere else in
 * this arc - never treated as "this row is a dead end."
 *
 * <p>Two categories of starting row are tested, for a broader real sample: adversarial
 * (biased toward the catalog's own lowest-degree biomes, via {@link
 * ReachabilityScoutingChecks#buildLowDegreeAdversarialRow} - reused, not duplicated) and
 * regular (an ordinary coverage-biased row, via {@link PeriodicCoherenceProof#tryBuildLegalRow}
 * with no previous-row constraint).
 */
public final class ConstructivePathfindingProof {

    private static final Path REPORT_PATH = Path.of("doc", "graph-coherence-constructive-pathfinding-report.txt");
    private static final long RANDOM_SEED = 20260916L;
    private static final int MIN_CELLS_PER_ROW = 6;
    private static final int MAX_CELLS_PER_ROW = 16;
    // Per-row internal retry budget (inside tryBuildLegalRow) - modest, since the fixed
    // width-estimate search now typically succeeds within a handful of attempts.
    private static final int ROW_BUILD_RETRIES = 200;
    // How many MORE rows to append, per attempt, before giving up on reaching alignment.
    private static final int MAX_PATH_DEPTH = 15;
    // Independent restarts of the WHOLE path per starting row - mirrors the same
    // attempt-and-restart pattern used everywhere else in this arc, rather than a second,
    // separate backtracking mechanism.
    private static final int PATH_ATTEMPTS_PER_START = 100;
    private static final int ADVERSARIAL_STARTING_ROWS = 5;
    private static final int REGULAR_STARTING_ROWS = 5;

    private ConstructivePathfindingProof() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        runProof(RawRuleTable.from(data), REPORT_PATH, "production ModBiomeGraph.OVERWORLD (src/main/resources/biomegraph/biome-graph.json)");
    }

    public static void runProof(RawRuleTable table, Path reportPath, String label) throws IOException {
        WitnessSearchResult searchResult = PeriodicCoherenceProof.findWitness(table);
        if (searchResult.layout() == null) {
            writeNoWitnessReport(reportPath, label, searchResult.totalAttempts());
            System.out.println("NO WITNESS FOUND - cannot search for paths to alignment without one.");
            System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
            return;
        }
        PeriodicRowLayout witness = searchResult.layout();
        Random random = new Random(RANDOM_SEED);
        Map<BiomeId, Integer> degreeById = ReachabilityScoutingChecks.computeDegrees(table);

        List<PathResult> results = new ArrayList<>();
        for (int i = 0; i < ADVERSARIAL_STARTING_ROWS; i++) {
            Row start = ReachabilityScoutingChecks.buildLowDegreeAdversarialRow(
                table, degreeById, witness.rowHeightBlocks(), witness.circumferenceBlocks(), random
            );
            results.add(searchFromStart("adversarial-" + (i + 1), start, witness, table, random));
        }
        for (int i = 0; i < REGULAR_STARTING_ROWS; i++) {
            Row start = PeriodicCoherenceProof.tryBuildLegalRow(
                table, new HashSet<>(), null, null, MIN_CELLS_PER_ROW, MAX_CELLS_PER_ROW, witness.rowHeightBlocks(), witness.circumferenceBlocks(),
                random, ROW_BUILD_RETRIES
            );
            results.add(searchFromStart("regular-" + (i + 1), start, witness, table, random));
        }

        writeReport(witness, results, reportPath, label);
        long found = results.stream().filter(r -> r.alignedWitnessRowIndex() != null).count();
        System.out.println("Found a path to alignment for " + found + "/" + results.size() + " starting rows tested.");
        System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
    }

    /** {@code path} is empty and {@code alignedWitnessRowIndex} is null if the starting row itself couldn't even be built. */
    public record PathResult(String label, List<Row> path, Integer alignedWitnessRowIndex) {
    }

    /**
     * Real, measured redesign (first version searched blind - random-walk landing on one
     * SPECIFIC ~11-cell witness sequence by chance is astronomically unlikely, and found
     * 0/10 alignments in the first real run). Guided instead: at every depth, before taking
     * any random step, directly test whether ANY of the witness's own rows can legally
     * attach to the current row RIGHT NOW (65 cheap {@link
     * PeriodicCoherenceProof#isVerticallyLegal} checks, no search) - if so, that IS the
     * alignment, by construction. Only when none of the 65 fit directly does the search take
     * one random intermediate step and try again from there.
     */
    static PathResult searchFromStart(String label, Row start, PeriodicRowLayout witness, RawRuleTable table, Random random) {
        if (start == null) {
            return new PathResult(label, List.of(), null);
        }
        Optional<Integer> immediateAlignment = checkAlignment(witness, start);
        if (immediateAlignment.isPresent()) {
            return new PathResult(label, List.of(start), immediateAlignment.get());
        }
        for (int attempt = 0; attempt < PATH_ATTEMPTS_PER_START; attempt++) {
            List<Row> path = new ArrayList<>();
            path.add(start);
            Row current = start;
            for (int depth = 0; depth < MAX_PATH_DEPTH; depth++) {
                Optional<Integer> directJump = directlyAttachableWitnessRow(witness, current, table);
                if (directJump.isPresent()) {
                    path.add(witness.rows().get(directJump.get()));
                    return new PathResult(label, path, directJump.get());
                }
                Row next = PeriodicCoherenceProof.tryBuildLegalRow(
                    table, new HashSet<>(), current, null, MIN_CELLS_PER_ROW, MAX_CELLS_PER_ROW, witness.rowHeightBlocks(),
                    witness.circumferenceBlocks(), random, ROW_BUILD_RETRIES
                );
                if (next == null) {
                    break; // this attempt's own walk dead-ended - try a fresh attempt from the same start, not necessarily the start's fault
                }
                path.add(next);
                current = next;
            }
        }
        return new PathResult(label, List.of(start), null); // started fine, no alignment found within budget - inconclusive, not a dead end
    }

    /** @return the index of a witness row that is legal to attach directly onto {@code current}, if any - a guaranteed alignment, not a guess. */
    static Optional<Integer> directlyAttachableWitnessRow(PeriodicRowLayout witness, Row current, RawRuleTable table) {
        List<Row> rows = witness.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (PeriodicCoherenceProof.isVerticallyLegal(current, rows.get(i), table)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    static Optional<Integer> checkAlignment(PeriodicRowLayout witness, Row row) {
        List<BiomeId> sequence = row.cells().stream().map(Cell::biomeId).toList();
        return EscapeHatchExtendabilityProof.matchingWitnessRowIndex(witness, sequence, witness.rowHeightBlocks(), witness.circumferenceBlocks());
    }

    private static void writeReport(PeriodicRowLayout witness, List<PathResult> results, Path reportPath, String label) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("CONSTRUCTIVE PATHFINDING - real, individual certificates, not a universal proof\n");
        report.append("===================================================================================\n\n");
        report.append("Checked against: ").append(label).append("\n\n");
        report.append("Witness: rowCount=").append(witness.rows().size()).append(", rowHeightBlocks=").append(witness.rowHeightBlocks())
            .append(", circumferenceBlocks=").append(witness.circumferenceBlocks()).append("\n\n");
        report.append("Each result below is either a real, replayable, checkable path from that ONE starting\n");
        report.append("row to a SafeStates-aligned row, or an honest INCONCLUSIVE (search budget exhausted,\n");
        report.append("never treated as proof that starting row is a dead end).\n\n");

        for (PathResult result : results) {
            if (result.path().isEmpty()) {
                report.append(result.label()).append(": could not even build a starting row within budget\n\n");
                continue;
            }
            if (result.alignedWitnessRowIndex() == null) {
                report.append(result.label()).append(": INCONCLUSIVE - no alignment found within ")
                    .append(PATH_ATTEMPTS_PER_START).append(" attempts x ").append(MAX_PATH_DEPTH).append(" rows deep\n");
                report.append("  starting row: [").append(String.join(", ", biomeSequenceOf(result.path().get(0)))).append("]\n\n");
                continue;
            }
            report.append(result.label()).append(": PATH FOUND - ").append(result.path().size()).append(" row(s), aligned to witness row ")
                .append(result.alignedWitnessRowIndex()).append("\n");
            int rowIndex = 0;
            for (Row row : result.path()) {
                report.append("  step ").append(rowIndex++).append(" [").append(row.cells().size()).append(" cells]: [")
                    .append(String.join(", ", biomeSequenceOf(row))).append("]\n");
            }
            report.append("  -> from here, EscapeHatchExtendabilityProof.escapeContinuationFor(witness, ")
                .append(result.alignedWitnessRowIndex()).append(") gives the safe continuation forever.\n\n");
        }

        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }

    private static List<String> biomeSequenceOf(Row row) {
        return row.cells().stream().map(cell -> cell.biomeId().toString()).toList();
    }

    private static void writeNoWitnessReport(Path reportPath, String label, int totalAttempts) throws IOException {
        String report = "CONSTRUCTIVE PATHFINDING - NO WITNESS\n"
            + "========================================\n\n"
            + "Checked against: " + label + "\n\n"
            + "PeriodicCoherenceProof.findWitness found no witness after " + totalAttempts + " attempts - this\n"
            + "class needs a real witness to search toward, and has nothing to check.\n";
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
    }
}
