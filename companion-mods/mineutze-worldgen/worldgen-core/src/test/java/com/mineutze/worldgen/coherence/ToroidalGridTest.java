package com.mineutze.worldgen.coherence;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToroidalGridTest {

    private static final int WIDTH = 5;
    private static final int HEIGHT = 4;

    @Test
    void everyCellHasSixDistinctNeighbors() {
        ToroidalGrid grid = new ToroidalGrid(WIDTH, HEIGHT);

        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            List<ToroidalPos> neighbors = grid.neighborsOf(pos);

            assertEquals(6, neighbors.size(), "neighbor count of " + pos);
            assertEquals(6, new HashSet<>(neighbors).size(), "neighbors of " + pos + " must be distinct");
        }
    }

    @Test
    void adjacencyIsSymmetric() {
        ToroidalGrid grid = new ToroidalGrid(WIDTH, HEIGHT);

        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                assertTrue(grid.neighborsOf(neighbor).contains(pos), pos + " lists " + neighbor + " but not the other way round");
            }
        }
    }

    @Test
    void cellsOnTheEdgeWrapAroundToTheOppositeEdge() {
        ToroidalGrid grid = new ToroidalGrid(WIDTH, HEIGHT);

        List<ToroidalPos> neighbors = grid.neighborsOf(new ToroidalPos(0, 0));

        assertTrue(neighbors.contains(new ToroidalPos(WIDTH - 1, 0)), "left of column 0 wraps to the last column");
        assertTrue(neighbors.contains(new ToroidalPos(0, HEIGHT - 1)), "above row 0 wraps to the last row");
        assertTrue(neighbors.contains(new ToroidalPos(1, 0)));
        assertTrue(neighbors.contains(new ToroidalPos(0, 1)));
    }

    /** The property the whole hex-lattice feasibility analysis rests on: two neighbors that are consecutive around a cell touch each other, so every cell plus two consecutive neighbors forms a mutually adjacent triple. */
    @Test
    void consecutiveNeighborsAroundACellAreThemselvesAdjacent() {
        ToroidalGrid grid = new ToroidalGrid(WIDTH, WIDTH);

        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            List<ToroidalPos> ring = grid.neighborsOf(pos);
            for (int index = 0; index < ring.size(); index++) {
                ToroidalPos current = ring.get(index);
                ToroidalPos next = ring.get((index + 1) % ring.size());

                assertTrue(grid.neighborsOf(current).contains(next), current + " and " + next + " are consecutive around " + pos + " but not adjacent");
            }
        }
    }

    @Test
    void fillOrderVisitsEveryCellOnceRowByRow() {
        ToroidalGrid grid = new ToroidalGrid(WIDTH, HEIGHT);

        List<ToroidalPos> order = grid.allPositionsInFillOrder();

        assertEquals(WIDTH * HEIGHT, order.size());
        assertEquals(WIDTH * HEIGHT, new HashSet<>(order).size(), "every cell exactly once");
        assertEquals(new ToroidalPos(0, 0), order.get(0));
        assertEquals(new ToroidalPos(1, 0), order.get(1), "q is the inner loop");
        assertEquals(new ToroidalPos(0, 1), order.get(WIDTH), "r advances after a full row");
    }

    @Test
    void cellCountIsWidthTimesHeight() {
        assertEquals(20, new ToroidalGrid(WIDTH, HEIGHT).cellCount());
    }

    @Test
    void constructorRejectsANonPositiveWidth() {
        assertThrows(IllegalArgumentException.class, () -> new ToroidalGrid(0, HEIGHT));
    }

    @Test
    void constructorRejectsANonPositiveHeight() {
        assertThrows(IllegalArgumentException.class, () -> new ToroidalGrid(WIDTH, -1));
    }
}
