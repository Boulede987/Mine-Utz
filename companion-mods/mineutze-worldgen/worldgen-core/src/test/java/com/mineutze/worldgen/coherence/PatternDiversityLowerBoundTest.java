package com.mineutze.worldgen.coherence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternDiversityLowerBoundTest {

    /** Real correctness requirement: no two selected positions may be adjacent, cyclically (wraparound included) - the whole independence argument depends on this. */
    private static void assertNoTwoPositionsAreAdjacent(List<Integer> positions, int rowCount) {
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                int a = positions.get(i);
                int b = positions.get(j);
                int cyclicDistance = Math.min(Math.floorMod(a - b, rowCount), Math.floorMod(b - a, rowCount));
                assertTrue(cyclicDistance >= 2, "positions " + a + " and " + b + " are adjacent (cyclic distance " + cyclicDistance + ") in a " + rowCount + "-row cycle");
            }
        }
    }

    @Test
    void oddRowCountDropsTheWraparoundConflictingPosition() {
        // 65 rows (the real production witness size): naive alternating 0,2,...,64 would wrap position 64 adjacent to position 0.
        List<Integer> positions = PatternDiversityLowerBound.nonAdjacentPositions(65);
        assertFalse(positions.contains(64), "position 64 would wrap adjacent to position 0 in a 65-row cycle - must be dropped");
        assertNoTwoPositionsAreAdjacent(positions, 65);
        assertEquals(32, positions.size());
    }

    @Test
    void evenRowCountKeepsEveryOtherPosition() {
        List<Integer> positions = PatternDiversityLowerBound.nonAdjacentPositions(80);
        assertNoTwoPositionsAreAdjacent(positions, 80);
        assertEquals(40, positions.size());
    }

    @Test
    void smallRowCountsStayCorrect() {
        for (int rowCount = 4; rowCount <= 20; rowCount++) {
            List<Integer> positions = PatternDiversityLowerBound.nonAdjacentPositions(rowCount);
            assertNoTwoPositionsAreAdjacent(positions, rowCount);
            assertTrue(positions.size() >= 1, "rowCount=" + rowCount + " should still yield at least 1 diversifiable position");
        }
    }
}
