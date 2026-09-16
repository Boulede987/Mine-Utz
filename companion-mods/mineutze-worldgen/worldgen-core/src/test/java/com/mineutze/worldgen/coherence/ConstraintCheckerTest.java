package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.RequiredNeighbor;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.ConstraintChecker.Result;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small hand-built synthetic graphs, not the real catalog - proves {@link
 * ConstraintChecker}'s three checks are each individually correct before ever trusting
 * them against real data.
 */
class ConstraintCheckerTest {

    private static final BiomeId A = new BiomeId("test", "a");
    private static final BiomeId B = new BiomeId("test", "b");
    private static final BiomeId C = new BiomeId("test", "c");

    /** A-B legal, A-C legal, B-C NOT legal (absent from the edge list). A requires >=1 B neighbor. */
    private static RawRuleTable tableWithAnIllegalPair() {
        BiomeGraphData data = new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 500, 800, Integer.MAX_VALUE, List.of(new RequiredNeighbor(B.namespace(), B.path(), 1))),
                new NodeData(B.namespace(), B.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(C.namespace(), C.path(), 500, 800, Integer.MAX_VALUE, List.of())
            ),
            List.of(
                new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10),
                new EdgeData(A.namespace(), A.path(), C.namespace(), C.path(), 10)
            )
        );
        return RawRuleTable.from(data);
    }

    /** Every pair among A/B/C legal. A requires >=1 B neighbor. */
    private static RawRuleTable tableFullyLegal() {
        BiomeGraphData data = new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 500, 800, Integer.MAX_VALUE, List.of(new RequiredNeighbor(B.namespace(), B.path(), 1))),
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

    @Test
    void detectsAnIllegalAdjacency() {
        Row row = new Row(List.of(new Cell(B, 5, 0), new Cell(C, 5, 5)));
        PeriodicRowLayout layout = new PeriodicRowLayout(10, 10, List.of(row));
        Result result = ConstraintChecker.check(layout, tableWithAnIllegalPair());
        assertFalse(result.illegalAdjacencies().isEmpty(), "B-C is not a legal pair in this fixture");
    }

    @Test
    void detectsAnUnmetRequiredNeighbor() {
        // A only ever touches C here (row wraps A-C-A), never B - A's own "requires >=1 B" is unmet.
        Row row = new Row(List.of(new Cell(A, 5, 0), new Cell(C, 5, 5)));
        PeriodicRowLayout layout = new PeriodicRowLayout(10, 10, List.of(row));
        Result result = ConstraintChecker.check(layout, tableWithAnIllegalPair());
        assertFalse(result.unmetRequiredNeighbors().isEmpty());
    }

    @Test
    void detectsAMissingDeclaredBiome() {
        // Table declares A, B, C - this layout never places B anywhere.
        Row row = new Row(List.of(new Cell(A, 5, 0), new Cell(C, 5, 5)));
        PeriodicRowLayout layout = new PeriodicRowLayout(10, 10, List.of(row));
        Result result = ConstraintChecker.check(layout, tableWithAnIllegalPair());
        assertTrue(result.missingBiomes().contains(B));
    }

    @Test
    void aFullyCorrectLayoutIsValid() {
        // row0: A touches B (satisfies A's own requirement). row1: C spans the whole
        // circumference, vertically overlapping BOTH A and B - legal either way in this
        // fixture, and puts all 3 declared biomes on the board.
        Row row0 = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5)));
        Row row1 = new Row(List.of(new Cell(C, 10, 0)));
        PeriodicRowLayout layout = new PeriodicRowLayout(10, 10, List.of(row0, row1));
        Result result = ConstraintChecker.check(layout, tableFullyLegal());
        assertTrue(result.isValid(), "illegal=" + result.illegalAdjacencies() + " unmet=" + result.unmetRequiredNeighbors() + " missing=" + result.missingBiomes());
    }
}
