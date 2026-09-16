package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.AdjacencyDerivation.Edge;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjacencyDerivationTest {

    private static final BiomeId A = new BiomeId("test", "a");
    private static final BiomeId B = new BiomeId("test", "b");
    private static final BiomeId C = new BiomeId("test", "c");
    private static final BiomeId D = new BiomeId("test", "d");

    @Test
    void overlappingIntervalsOverlap() {
        assertTrue(AdjacencyDerivation.intervalsOverlap(0, 10, 5, 10)); // [0,10) and [5,15) share [5,10)
    }

    @Test
    void disjointIntervalsDoNotOverlap() {
        assertFalse(AdjacencyDerivation.intervalsOverlap(0, 10, 20, 10)); // [0,10) and [20,30)
    }

    @Test
    void exactlyTouchingIntervalsDoNotOverlap() {
        // [0,10) and [10,20) share only the single boundary point 10 - zero-width, not a real overlap.
        assertFalse(AdjacencyDerivation.intervalsOverlap(0, 10, 10, 10));
    }

    @Test
    void deriveAllIncludesHorizontalWraparoundEdge() {
        Row row = new Row(List.of(new Cell(A, 5, 0), new Cell(B, 5, 5), new Cell(C, 5, 10)));
        PeriodicRowLayout layout = new PeriodicRowLayout(10, 15, List.of(row));
        List<Edge> edges = AdjacencyDerivation.deriveAll(layout);
        boolean hasWraparoundEdge = edges.stream().anyMatch(e -> containsPair(e, A, C));
        assertTrue(hasWraparoundEdge, "cell C (last) should be horizontally adjacent to cell A (first) via wraparound");
    }

    @Test
    void deriveAllFindsVerticalOverlapBetweenRows() {
        Row lower = new Row(List.of(new Cell(A, 10, 0)));
        Row upper = new Row(List.of(new Cell(B, 10, 0)));
        PeriodicRowLayout layout = new PeriodicRowLayout(10, 10, List.of(lower, upper));
        List<Edge> edges = AdjacencyDerivation.deriveAll(layout);
        assertTrue(edges.stream().anyMatch(e -> containsPair(e, A, B)));
    }

    @Test
    void deriveAllOmitsVerticalEdgeWhenCellsDoNotOverlap() {
        Row lower = new Row(List.of(new Cell(A, 5, 0), new Cell(D, 5, 5)));
        Row upper = new Row(List.of(new Cell(B, 5, 0), new Cell(C, 5, 5)));
        PeriodicRowLayout layout = new PeriodicRowLayout(10, 10, List.of(lower, upper));
        List<Edge> edges = AdjacencyDerivation.deriveAll(layout);
        assertFalse(edges.stream().anyMatch(e -> containsPair(e, A, C)), "A [0,5) and C [5,10) should not overlap");
        assertFalse(edges.stream().anyMatch(e -> containsPair(e, D, B)), "D [5,10) and B [0,5) should not overlap");
    }

    private static boolean containsPair(Edge edge, BiomeId x, BiomeId y) {
        boolean forward = edge.a().biomeId().equals(x) && edge.b().biomeId().equals(y);
        boolean backward = edge.a().biomeId().equals(y) && edge.b().biomeId().equals(x);
        return forward || backward;
    }
}
