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
import java.util.List;
import java.util.Optional;

/**
 * A real, closed extendability result - deliberately NOT the strong, unrestricted claim
 * ("every valid partial state can be extended"), which is already known FALSE for this
 * rule graph (real production dead ends - worldSeed=2029, region[-2,8], the 28-seed
 * diagnostic sweep - are literal counterexamples; 0 failures across 100,000 sampled seeds
 * has already been observed to flip to a dozen after a few real graph edits, so sampling
 * was never proof of "always extendable" either, only ever proof of "found a failure" when
 * it happens to hit one). Instead, this proves the weaker but REAL, useful claim the
 * pasted-in framework called "SafeStates":
 *
 * <blockquote>
 * Define {@code SafeStates} = every finite row sequence whose LAST row is, cell-for-cell,
 * identical to one of the {@link PeriodicCoherenceProof}-discovered witness's own rows
 * (same biome sequence, same {@code rowHeightBlocks}, same {@code circumferenceBlocks}).
 * Every state in {@code SafeStates} has a safe continuation: the witness's own next row,
 * cyclically. Therefore any construction that ever reaches a witness-aligned state can
 * continue forever, deterministically, with zero further risk.
 * </blockquote>
 *
 * <p><b>Why this is nearly free to prove, not a new search:</b> {@link
 * ConstraintChecker#check} already verified the witness's ENTIRE cycle - including the
 * wraparound seam - when {@link PeriodicCoherenceProof} first found it. Every one of the
 * {@code rowCount} row-to-row transitions this class checks is therefore already known
 * legal; this class independently RE-verifies each one directly (not "trust the earlier
 * run") and packages the result as an explicit, checkable, reusable theorem - {@link
 * #matchingWitnessRowIndex} is how a caller checks whether some OTHER state is a member of
 * {@code SafeStates}, {@link #escapeContinuationFor} is the actual rescue row to append if
 * so.
 *
 * <p><b>What this explicitly does NOT prove</b> (real, deferred, not solved here): that
 * every legal partial construction can ever REACH a witness-aligned state in the first
 * place. Given the counterexamples above, the unrestricted version of that claim is
 * probably false too - some reachable configurations likely can't be reconciled with this
 * one witness at all. This class is honest infrastructure for a future repair/rescue
 * mechanism, not a claim that nothing can go wrong upstream of it.
 */
public final class EscapeHatchExtendabilityProof {

    private static final Path REPORT_PATH = Path.of("doc", "graph-coherence-escape-hatch-extendability-report.txt");

    private EscapeHatchExtendabilityProof() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        runProof(RawRuleTable.from(data), REPORT_PATH, "production ModBiomeGraph.OVERWORLD (src/main/resources/biomegraph/biome-graph.json)");
    }

    /**
     * Finds a witness (via {@link PeriodicCoherenceProof#findWitness}, same search, not
     * duplicated) then independently re-verifies every row-to-row transition in it.
     */
    public static void runProof(RawRuleTable table, Path reportPath, String label) throws IOException {
        WitnessSearchResult searchResult = PeriodicCoherenceProof.findWitness(table);
        if (searchResult.layout() == null) {
            writeNoWitnessReport(reportPath, label, searchResult.totalAttempts());
            System.out.println("NO WITNESS FOUND - cannot build an escape-hatch proof without one (proof #1 must succeed first).");
            System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
            return;
        }
        PeriodicRowLayout witness = searchResult.layout();
        List<TransitionCheck> transitions = verifyAllTransitions(witness, table);
        boolean allLegal = transitions.stream().allMatch(TransitionCheck::legal);
        writeReport(witness, transitions, allLegal, table, reportPath, label);
        System.out.println(
            (allLegal ? "ESCAPE-HATCH THEOREM CONFIRMED" : "ESCAPE-HATCH CHECK FOUND A FAILURE (would be a real bug elsewhere, never expected)")
                + " - " + transitions.size() + " row-to-row transitions independently re-verified."
        );
        System.out.println("Report link: " + reportPath.toAbsolutePath().toUri());
    }

    /** One witness row's own transition to its cyclic successor, and whether that transition is legal. */
    public record TransitionCheck(int fromRowIndex, int toRowIndex, boolean legal) {
    }

    /** Independently re-checks every row-to-row transition in the witness, including the wraparound pair (last row to row 0). */
    static List<TransitionCheck> verifyAllTransitions(PeriodicRowLayout witness, RawRuleTable table) {
        List<Row> rows = witness.rows();
        int rowCount = rows.size();
        List<TransitionCheck> results = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            int next = (i + 1) % rowCount;
            boolean legal = PeriodicCoherenceProof.isVerticallyLegal(rows.get(i), rows.get(next), table);
            results.add(new TransitionCheck(i, next, legal));
        }
        return results;
    }

    /**
     * The actual membership test for {@code SafeStates}: does some OTHER partial
     * construction's own last row align with one of the witness's rows? Requires an exact
     * match on {@code rowHeightBlocks}/{@code circumferenceBlocks} too - {@link
     * RowFeasibility#distributeWidths} is a deterministic function of (biome sequence, row
     * height, circumference), so matching the biome sequence AND those two parameters
     * guarantees the exact same widths/positions as the witness row, without needing a
     * separate floating-point comparison.
     *
     * @return the witness row index this candidate aligns with, if any.
     */
    public static Optional<Integer> matchingWitnessRowIndex(
        PeriodicRowLayout witness, List<BiomeId> candidateLastRowBiomeSequence, double candidateRowHeightBlocks, double candidateCircumferenceBlocks
    ) {
        if (candidateRowHeightBlocks != witness.rowHeightBlocks() || candidateCircumferenceBlocks != witness.circumferenceBlocks()) {
            return Optional.empty();
        }
        List<Row> rows = witness.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (biomeSequenceOf(rows.get(i)).equals(candidateLastRowBiomeSequence)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /** The actual rescue row to append once {@link #matchingWitnessRowIndex} confirms alignment - the witness's own cyclic next row. */
    public static Row escapeContinuationFor(PeriodicRowLayout witness, int matchedWitnessRowIndex) {
        int next = (matchedWitnessRowIndex + 1) % witness.rows().size();
        return witness.rows().get(next);
    }

    private static List<BiomeId> biomeSequenceOf(Row row) {
        return row.cells().stream().map(Cell::biomeId).toList();
    }

    private static void writeReport(
        PeriodicRowLayout witness, List<TransitionCheck> transitions, boolean allLegal, RawRuleTable table, Path reportPath, String label
    ) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append(allLegal ? "ESCAPE-HATCH EXTENDABILITY PROOF - CONFIRMED\n" : "ESCAPE-HATCH EXTENDABILITY PROOF - FAILED (real bug)\n");
        report.append("=================================================\n\n");
        report.append("Checked against: ").append(label).append("\n\n");
        report.append("Theorem: define SafeStates = every finite row sequence whose LAST row exactly\n");
        report.append("matches (same biome sequence, same rowHeightBlocks, same circumferenceBlocks) one\n");
        report.append("of this witness's own rows. Every state in SafeStates has a safe continuation:\n");
        report.append("the witness's own cyclic next row.\n\n");
        report.append("This does NOT prove every valid partial state can reach a witness-aligned state -\n");
        report.append("that question is open, deliberately deferred (see this class's own doc). Real\n");
        report.append("production dead ends already prove the UNRESTRICTED extendability claim false, so\n");
        report.append("this class only ever claims the restricted, SafeStates version.\n\n");
        report.append("rowCount=").append(witness.rows().size()).append(", rowHeightBlocks=").append(witness.rowHeightBlocks())
            .append(", circumferenceBlocks=").append(witness.circumferenceBlocks()).append("\n\n");

        report.append("Row-to-row transitions (all ").append(transitions.size()).append(" independently re-verified, not assumed from the earlier witness search):\n");
        for (TransitionCheck transition : transitions) {
            Row fromRow = witness.rows().get(transition.fromRowIndex());
            report.append("  row ").append(transition.fromRowIndex()).append(" -> row ").append(transition.toRowIndex())
                .append(": ").append(transition.legal() ? "LEGAL" : "ILLEGAL (unexpected!)")
                .append("  [").append(fromRow.cells().size()).append(" cells: ")
                .append(String.join(", ", biomeSequenceOf(fromRow).stream().map(BiomeId::toString).toList())).append("]\n");
        }

        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }

    private static void writeNoWitnessReport(Path reportPath, String label, int totalAttempts) throws IOException {
        String report = "ESCAPE-HATCH EXTENDABILITY PROOF - NO WITNESS\n"
            + "================================================\n\n"
            + "Checked against: " + label + "\n\n"
            + "PeriodicCoherenceProof.findWitness found no witness after " + totalAttempts + " attempts - this\n"
            + "class needs a real witness to build the escape hatch around, and has nothing to check.\n"
            + "This is NOT a proof the graph is incoherent - see PeriodicCoherenceProof's own report for\n"
            + "the same honest inconclusive-vs-impossible distinction.\n";
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
    }
}
