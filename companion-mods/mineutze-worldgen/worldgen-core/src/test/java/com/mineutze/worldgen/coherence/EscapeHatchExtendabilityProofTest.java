package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.EscapeHatchExtendabilityProof.TransitionCheck;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small hand-built synthetic graphs, not the real catalog - proves the escape-hatch
 * machinery is correct in BOTH directions (confirms a real legal cycle, and actually
 * detects a deliberately broken one) before ever trusting it against real data.
 */
class EscapeHatchExtendabilityProofTest {

    private static final BiomeId A = new BiomeId("test", "a");
    private static final BiomeId B = new BiomeId("test", "b");
    private static final BiomeId C = new BiomeId("test", "c");

    /** Every pair among A/B/C legal - a genuine 2-row cycle built from this fixture has no illegal transition. */
    private static RawRuleTable tableFullyLegal() {
        BiomeGraphData data = new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(B.namespace(), B.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(C.namespace(), C.path(), 500, 800, Integer.MAX_VALUE, List.of())
            ),
            List.of(
                new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10),
                new EdgeData(A.namespace(), A.path(), C.namespace(), C.path(), 10),
                new EdgeData(B.namespace(), B.path(), C.namespace(), C.path(), 10)
            )
        );
        return RawRuleTable.from(data);
    }

    /** A-B legal, but B-C NOT legal - a row pair using both breaks the cycle deliberately. */
    private static RawRuleTable tableWithAnIllegalPair() {
        BiomeGraphData data = new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(B.namespace(), B.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(C.namespace(), C.path(), 500, 800, Integer.MAX_VALUE, List.of())
            ),
            List.of(new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10))
        );
        return RawRuleTable.from(data);
    }

    @Test
    void verifyAllTransitionsConfirmsARealLegalCycle() {
        Row row0 = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        Row row1 = new Row(List.of(new Cell(C, 10, 0)));
        PeriodicRowLayout witness = new PeriodicRowLayout(10, 10, List.of(row0, row1));

        List<TransitionCheck> transitions = EscapeHatchExtendabilityProof.verifyAllTransitions(witness, tableFullyLegal());
        assertEquals(2, transitions.size());
        assertTrue(transitions.stream().allMatch(TransitionCheck::legal));
    }

    @Test
    void verifyAllTransitionsDetectsADeliberatelyIllegalTransition() {
        // row0 (B,C) vertically touches row1 (single cell spanning both) - B-C is NOT legal in this fixture.
        Row row0 = new Row(List.of(new Cell(B, 5, 0), new Cell(C, 5, 5)));
        Row row1 = new Row(List.of(new Cell(A, 10, 0)));
        PeriodicRowLayout brokenWitness = new PeriodicRowLayout(10, 10, List.of(row0, row1));

        List<TransitionCheck> transitions = EscapeHatchExtendabilityProof.verifyAllTransitions(brokenWitness, tableWithAnIllegalPair());
        assertTrue(transitions.stream().anyMatch(t -> !t.legal()), "row0->row1 touches B and C, which are not a legal pair in this fixture");
    }

    @Test
    void matchingWitnessRowIndexFindsAnExactMatch() {
        Row row0 = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        Row row1 = new Row(List.of(new Cell(C, 10, 0)));
        PeriodicRowLayout witness = new PeriodicRowLayout(10, 10, List.of(row0, row1));

        Optional<Integer> match = EscapeHatchExtendabilityProof.matchingWitnessRowIndex(witness, List.of(A, B), 10, 10);
        assertEquals(Optional.of(0), match);
    }

    @Test
    void matchingWitnessRowIndexRejectsAMismatchedRowHeight() {
        Row row0 = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        PeriodicRowLayout witness = new PeriodicRowLayout(10, 10, List.of(row0));

        Optional<Integer> match = EscapeHatchExtendabilityProof.matchingWitnessRowIndex(witness, List.of(A, B), 99, 10);
        assertFalse(match.isPresent(), "same biome sequence but a different rowHeightBlocks must not count as aligned");
    }

    @Test
    void matchingWitnessRowIndexRejectsADifferentBiomeSequence() {
        Row row0 = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        PeriodicRowLayout witness = new PeriodicRowLayout(10, 10, List.of(row0));

        Optional<Integer> match = EscapeHatchExtendabilityProof.matchingWitnessRowIndex(witness, List.of(B, A), 10, 10);
        assertFalse(match.isPresent(), "reversed order is a different sequence, not a match");
    }

    @Test
    void escapeContinuationWrapsAroundToRowZero() {
        Row row0 = new Row(List.of(new Cell(A, 10, 0)));
        Row row1 = new Row(List.of(new Cell(B, 10, 0)));
        PeriodicRowLayout witness = new PeriodicRowLayout(10, 10, List.of(row0, row1));

        assertEquals(row1, EscapeHatchExtendabilityProof.escapeContinuationFor(witness, 0));
        assertEquals(row0, EscapeHatchExtendabilityProof.escapeContinuationFor(witness, 1)); // wraps: last row's continuation is row 0
    }
}
