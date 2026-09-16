package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.ConstructivePathfindingProof.PathResult;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small hand-built synthetic graphs, not the real catalog - proves the search mechanism
 * (alignment check, direct-attach check, the overall path search) is correct in isolation
 * before trusting the real 10/10 result against production data.
 */
class ConstructivePathfindingProofTest {

    private static final BiomeId A = new BiomeId("test", "a");
    private static final BiomeId B = new BiomeId("test", "b");
    private static final BiomeId C = new BiomeId("test", "c");

    /** Every pair among A/B/C legal - any row is directly attachable to any other, the easy case. */
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

    /** A-B legal, A-C legal, B-C NOT legal - real selectivity to test "no direct attach exists." */
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

    private static PeriodicRowLayout twoRowWitness() {
        Row row0 = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        Row row1 = new Row(List.of(new Cell(C, 10, 0)));
        return new PeriodicRowLayout(10, 10, List.of(row0, row1));
    }

    @Test
    void checkAlignmentMatchesAnIdenticalRow() {
        PeriodicRowLayout witness = twoRowWitness();
        Row candidate = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        assertEquals(Optional.of(0), ConstructivePathfindingProof.checkAlignment(witness, candidate));
    }

    @Test
    void checkAlignmentRejectsADifferentSequence() {
        PeriodicRowLayout witness = twoRowWitness();
        Row candidate = new Row(List.of(new Cell(B, 5, 0), new Cell(A, 5, 5)));
        assertFalse(ConstructivePathfindingProof.checkAlignment(witness, candidate).isPresent());
    }

    @Test
    void directlyAttachableWitnessRowFindsALegalAttachment() {
        // row0=[B,C] touching a single-cell A row - B-A and C-A both legal in the fully-legal fixture.
        PeriodicRowLayout witness = twoRowWitness();
        Row current = new Row(List.of(new Cell(A, 10, 0)));
        Optional<Integer> attach = ConstructivePathfindingProof.directlyAttachableWitnessRow(witness, current, tableFullyLegal());
        assertTrue(attach.isPresent());
    }

    @Test
    void directlyAttachableWitnessRowFindsNoneWhenBothWitnessRowsAreIllegal() {
        // witness row0 uses B, row1 uses C - B-C illegal in this fixture, so a current row
        // containing B cannot attach row1(C) and vice versa is exercised via a row of just C
        // against row0 (which contains B, illegal) and row1 (self C, always illegal).
        PeriodicRowLayout witness = twoRowWitness();
        Row current = new Row(List.of(new Cell(C, 10, 0)));
        Optional<Integer> attach = ConstructivePathfindingProof.directlyAttachableWitnessRow(witness, current, tableWithAnIllegalPair());
        assertFalse(attach.isPresent(), "current=[C] overlaps row0's B cell (illegal) and row1 is C-vs-C (self, always illegal)");
    }

    @Test
    void searchFromStartReturnsImmediateAlignmentWhenStartAlreadyMatches() {
        PeriodicRowLayout witness = twoRowWitness();
        Row start = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        PathResult result = ConstructivePathfindingProof.searchFromStart("test", start, witness, tableFullyLegal(), new Random(1));
        assertEquals(0, result.alignedWitnessRowIndex());
        assertEquals(1, result.path().size());
    }

    @Test
    void searchFromStartFindsADirectAttachFromADifferentStart() {
        PeriodicRowLayout witness = twoRowWitness();
        Row start = new Row(List.of(new Cell(A, 10, 0))); // doesn't match either witness row, but fully-legal means direct attach works
        PathResult result = ConstructivePathfindingProof.searchFromStart("test", start, witness, tableFullyLegal(), new Random(1));
        assertTrue(result.alignedWitnessRowIndex() != null);
        assertEquals(2, result.path().size(), "start + the directly-attached witness row");
    }

    @Test
    void searchFromStartHandlesANullStartGracefully() {
        PeriodicRowLayout witness = twoRowWitness();
        PathResult result = ConstructivePathfindingProof.searchFromStart("test", null, witness, tableFullyLegal(), new Random(1));
        assertTrue(result.path().isEmpty());
        assertEquals(null, result.alignedWitnessRowIndex());
    }
}
