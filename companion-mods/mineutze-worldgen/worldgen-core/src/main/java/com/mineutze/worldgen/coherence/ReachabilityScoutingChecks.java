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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Two CHEAP checks, run before committing real effort to the reachability question
 * (2026-09: "can an arbitrary legal construction always eventually reach a {@link
 * EscapeHatchExtendabilityProof} SafeState" - explicitly deferred there, scoped out here).
 * Neither is the real reachability proof - they're a fast sanity pass to see if the
 * question resolves trivially either way before investing in a bigger effort.
 *
 * <p><b>Check 1 - universal sink.</b> Is any witness row legal against literally any other
 * legal row, making alignment always 1 step away regardless of where a construction is?
 * Checked against all 64 OTHER real, already-certified-legal witness rows (not a
 * hypothetical exhaustive check over every conceivable row, which is infeasible - but a
 * real, 100% certain DISPROOF the moment any one of those 64 comparisons fails, since
 * these are definitely legal rows, not guesses).
 *
 * <p><b>Check 2 - isolated row.</b> Does a legal row built adversarially (biased toward the
 * lowest-degree real biomes, e.g. {@code deep_lukewarm_ocean} at degree 4) have ZERO legal
 * successors at all? A "yes" here would be a real, complete, constructive counterexample to
 * even the RESTRICTED reachability question, same evidentiary weight as the real dead-end
 * examples that already killed the unrestricted extendability claim. A "no" (search found
 * nothing within budget) is only ever inconclusive, per this whole project's established
 * discipline - bounded search absence is never proof of impossibility.
 */
public final class ReachabilityScoutingChecks {

    private static final Path REPORT_PATH = Path.of("doc", "graph-coherence-reachability-scouting-report.txt");
    private static final long RANDOM_SEED = 20260916L;
    private static final int MIN_CELLS_PER_ROW = 6;
    private static final int MAX_CELLS_PER_ROW = 16;
    private static final int ADVERSARIAL_ROW_BUILD_RETRIES = 500;
    private static final int SUCCESSOR_SEARCH_RETRIES = 2000;
    private static final int ADVERSARIAL_ROW_ATTEMPTS = 10;
    // Each step of the adversarial walk picks among its LOWEST-degree legal candidates,
    // not strictly the single lowest - some randomness so a real dead end early in one
    // attempt doesn't make every attempt identical (10 attempts of the same row would be
    // one data point wearing a disguise, not real coverage).
    private static final int ADVERSARIAL_CANDIDATE_POOL_SIZE = 3;

    private ReachabilityScoutingChecks() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        runChecks(RawRuleTable.from(data), REPORT_PATH, "production ModBiomeGraph.OVERWORLD (src/main/resources/biomegraph/biome-graph.json)");
    }

    public static void runChecks(RawRuleTable table, Path reportPath, String label) throws IOException {
        WitnessSearchResult searchResult = PeriodicCoherenceProof.findWitness(table);
        if (searchResult.layout() == null) {
            writeNoWitnessReport(reportPath, label, searchResult.totalAttempts());
            System.out.println("NO WITNESS FOUND - cannot scout reachability without one.");
            System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
            return;
        }
        PeriodicRowLayout witness = searchResult.layout();
        Random random = new Random(RANDOM_SEED);

        List<UniversalSinkResult> sinkResults = checkUniversalSink(witness, table);
        List<AdversarialRowResult> adversarialResults = new ArrayList<>();
        for (int i = 0; i < ADVERSARIAL_ROW_ATTEMPTS; i++) {
            adversarialResults.add(checkForIsolatedRow(witness, table, random));
        }

        writeReport(witness, sinkResults, adversarialResults, reportPath, label);

        long sinksFound = sinkResults.stream().filter(UniversalSinkResult::isUniversalSinkAmongWitnessRows).count();
        long isolatedFound = adversarialResults.stream().filter(r -> r.hardRowFound() && !r.successorFound()).count();
        System.out.println(
            "Check 1 (universal sink): " + sinksFound + "/" + sinkResults.size() + " witness rows are legal against ALL other witness rows."
        );
        System.out.println(
            "Check 2 (isolated row): " + isolatedFound + "/" + adversarialResults.size()
                + " adversarial rows found with NO successor after " + SUCCESSOR_SEARCH_RETRIES + " attempts."
        );
        System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
    }

    /** {@code counterexampleRowIndex} is null iff this row is legal against all 64 other witness rows (real, but not proof of TRUE universality - only 65 rows were ever tested against). */
    public record UniversalSinkResult(int rowIndex, boolean isUniversalSinkAmongWitnessRows, Integer counterexampleRowIndex) {
    }

    static List<UniversalSinkResult> checkUniversalSink(PeriodicRowLayout witness, RawRuleTable table) {
        List<Row> rows = witness.rows();
        List<UniversalSinkResult> results = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Integer counterexample = null;
            for (int j = 0; j < rows.size() && counterexample == null; j++) {
                if (i != j && !PeriodicCoherenceProof.isVerticallyLegal(rows.get(i), rows.get(j), table)) {
                    counterexample = j;
                }
            }
            results.add(new UniversalSinkResult(i, counterexample == null, counterexample));
        }
        return results;
    }

    /** {@code hardRowFound} false means even the adversarial row itself couldn't be built (a different, separate finding from "no successor"). */
    public record AdversarialRowResult(boolean hardRowFound, List<BiomeId> hardRowBiomeSequence, boolean successorFound) {
    }

    static AdversarialRowResult checkForIsolatedRow(PeriodicRowLayout witness, RawRuleTable table, Random random) {
        Map<BiomeId, Integer> degreeById = computeDegrees(table);
        Row hardRow = buildLowDegreeAdversarialRow(table, degreeById, witness.rowHeightBlocks(), witness.circumferenceBlocks(), random);
        if (hardRow == null) {
            return new AdversarialRowResult(false, List.of(), false);
        }
        Row successor = PeriodicCoherenceProof.tryBuildLegalRow(
            table, new HashSet<>(), hardRow, null, MIN_CELLS_PER_ROW, MAX_CELLS_PER_ROW, witness.rowHeightBlocks(), witness.circumferenceBlocks(),
            random, SUCCESSOR_SEARCH_RETRIES
        );
        return new AdversarialRowResult(true, biomeSequenceOf(hardRow), successor != null);
    }

    /** Package-visible: {@code ConstructivePathfindingProof} reuses this to build its own adversarial starting rows, rather than a second copy. */
    static Map<BiomeId, Integer> computeDegrees(RawRuleTable table) {
        List<BiomeId> allIds = table.allBiomeIds();
        Map<BiomeId, Integer> degreeById = new HashMap<>();
        for (BiomeId id : allIds) {
            degreeById.put(id, (int) allIds.stream().filter(other -> !other.equals(id) && table.isLegal(id, other)).count());
        }
        return degreeById;
    }

    /** Package-visible: {@code ConstructivePathfindingProof} reuses this to build its own adversarial starting rows, rather than a second copy. */
    static Row buildLowDegreeAdversarialRow(
        RawRuleTable table, Map<BiomeId, Integer> degreeById, double rowHeightBlocks, double circumferenceBlocks, Random random
    ) {
        for (int attempt = 0; attempt < ADVERSARIAL_ROW_BUILD_RETRIES; attempt++) {
            List<BiomeId> sequence = tryBuildLowDegreeSequence(table, degreeById, random);
            if (sequence == null) {
                continue;
            }
            RowFeasibility.WidthInterval interval = RowFeasibility.achievableInterval(sequence, table, rowHeightBlocks);
            if (!RowFeasibility.fits(circumferenceBlocks, interval)) {
                continue;
            }
            List<Cell> cells = RowFeasibility.distributeWidths(sequence, table, rowHeightBlocks, circumferenceBlocks);
            return new Row(cells);
        }
        return null;
    }

    private static List<BiomeId> tryBuildLowDegreeSequence(RawRuleTable table, Map<BiomeId, Integer> degreeById, Random random) {
        List<BiomeId> allIds = table.allBiomeIds();
        List<BiomeId> sortedByDegree = allIds.stream().sorted(Comparator.comparingInt(degreeById::get)).toList();
        int startPoolSize = Math.min(5, sortedByDegree.size());
        BiomeId first = sortedByDegree.get(random.nextInt(startPoolSize));

        int targetCellCount = MIN_CELLS_PER_ROW + random.nextInt(MAX_CELLS_PER_ROW - MIN_CELLS_PER_ROW + 1);
        List<BiomeId> sequence = new ArrayList<>();
        sequence.add(first);
        for (int i = 1; i < targetCellCount; i++) {
            BiomeId previous = sequence.get(sequence.size() - 1);
            boolean isClosingCell = i == targetCellCount - 1;
            List<BiomeId> candidates = allIds.stream().filter(id -> table.isLegal(previous, id)).toList();
            if (isClosingCell) {
                candidates = candidates.stream().filter(id -> table.isLegal(id, first)).toList();
            }
            if (candidates.isEmpty()) {
                return null;
            }
            List<BiomeId> sortedCandidates = candidates.stream().sorted(Comparator.comparingInt(degreeById::get)).toList();
            int poolSize = Math.min(ADVERSARIAL_CANDIDATE_POOL_SIZE, sortedCandidates.size());
            sequence.add(sortedCandidates.get(random.nextInt(poolSize)));
        }
        return sequence;
    }

    private static List<BiomeId> biomeSequenceOf(Row row) {
        return row.cells().stream().map(Cell::biomeId).toList();
    }

    private static void writeReport(
        PeriodicRowLayout witness, List<UniversalSinkResult> sinkResults, List<AdversarialRowResult> adversarialResults, Path reportPath, String label
    ) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("REACHABILITY SCOUTING CHECKS - not the reachability proof itself, a cheap pre-check\n");
        report.append("====================================================================================\n\n");
        report.append("Checked against: ").append(label).append("\n\n");
        report.append("Witness: rowCount=").append(witness.rows().size()).append(", rowHeightBlocks=").append(witness.rowHeightBlocks())
            .append(", circumferenceBlocks=").append(witness.circumferenceBlocks()).append("\n\n");

        report.append("--- Check 1: universal sink ---\n");
        report.append("Is any witness row legal against ALL 64 other witness rows (real, already-certified\n");
        report.append("legal rows - not a hypothetical exhaustive check, but a complete, certain disproof\n");
        report.append("the moment one comparison fails)?\n\n");
        long sinkCount = sinkResults.stream().filter(UniversalSinkResult::isUniversalSinkAmongWitnessRows).count();
        report.append("Result: ").append(sinkCount).append("/").append(sinkResults.size()).append(" rows are a sink among the 65 witness rows.\n");
        for (UniversalSinkResult result : sinkResults) {
            if (!result.isUniversalSinkAmongWitnessRows()) {
                report.append("  row ").append(result.rowIndex()).append(": NOT a sink - illegal against row ")
                    .append(result.counterexampleRowIndex()).append("\n");
            }
        }
        report.append("\n");

        report.append("--- Check 2: isolated (dead-end) row ---\n");
        report.append("Built ").append(adversarialResults.size()).append(" adversarial rows biased toward the LOWEST-degree\n");
        report.append("real biomes (e.g. deep_lukewarm_ocean, degree 4) - for each, searched up to ")
            .append(SUCCESSOR_SEARCH_RETRIES).append(" times\n");
        report.append("for ANY legal successor row at the witness's own circumference.\n\n");
        int attemptIndex = 0;
        for (AdversarialRowResult result : adversarialResults) {
            attemptIndex++;
            if (!result.hardRowFound()) {
                report.append("  attempt ").append(attemptIndex).append(": could not even construct an adversarial row within budget\n");
                continue;
            }
            report.append("  attempt ").append(attemptIndex).append(": row=[")
                .append(String.join(", ", result.hardRowBiomeSequence().stream().map(BiomeId::toString).toList())).append("] successor ")
                .append(result.successorFound() ? "FOUND" : "NOT FOUND (inconclusive at this budget, not proof of isolation)").append("\n");
        }
        report.append("\n");

        report.append("--- Honest interpretation ---\n");
        report.append("Neither check is the reachability proof. Check 1 finding zero sinks only rules out\n");
        report.append("the TRIVIAL positive case (it does not rule out a more complex sufficient condition).\n");
        report.append("Check 2 finding a successor for every adversarial row is reassuring but not a proof -\n");
        report.append("only an ISOLATED row (successor NOT FOUND) would be a real, complete, constructive\n");
        report.append("counterexample. See ReachabilityScoutingChecks's own class doc for what to do next\n");
        report.append("depending on which way these land.\n");

        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }

    private static void writeNoWitnessReport(Path reportPath, String label, int totalAttempts) throws IOException {
        String report = "REACHABILITY SCOUTING CHECKS - NO WITNESS\n"
            + "===========================================\n\n"
            + "Checked against: " + label + "\n\n"
            + "PeriodicCoherenceProof.findWitness found no witness after " + totalAttempts + " attempts - these\n"
            + "checks need a real witness to probe around, and have nothing to check.\n";
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
    }
}
