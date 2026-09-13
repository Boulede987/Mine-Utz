package com.mineutze.worldgen.regionsolve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct small-fixture tests - flagged by test-coverage audit as having zero dedicated tests despite real neighbor/hashCode logic beyond plain data holder. */
class MacroCellPosTest {

    @Test
    void orthogonalNeighborsAreExactlyTheFourAdjacentCells() {
        MacroCellPos cell = new MacroCellPos(5, 5);

        assertEquals(
            Set.of(new MacroCellPos(6, 5), new MacroCellPos(4, 5), new MacroCellPos(5, 6), new MacroCellPos(5, 4)),
            Set.copyOf(cell.orthogonalNeighbors())
        );
    }

    @Test
    void diagonalNeighborsAreExactlyTheFourCornerCells() {
        MacroCellPos cell = new MacroCellPos(5, 5);

        assertEquals(
            Set.of(new MacroCellPos(6, 6), new MacroCellPos(6, 4), new MacroCellPos(4, 6), new MacroCellPos(4, 4)),
            Set.copyOf(cell.diagonalNeighbors())
        );
    }

    @Test
    void mooreNeighborsIsExactlyOrthogonalPlusDiagonalWithNoDuplicates() {
        MacroCellPos cell = new MacroCellPos(0, 0);
        List<MacroCellPos> moore = cell.mooreNeighbors();

        assertEquals(8, moore.size());
        assertEquals(Set.copyOf(moore).size(), moore.size(), "no neighbor should appear twice");
        Set<MacroCellPos> expected = Set.copyOf(cell.orthogonalNeighbors());
        assertTrue(moore.containsAll(cell.orthogonalNeighbors()) && moore.containsAll(cell.diagonalNeighbors()));
    }

    @Test
    void distanceSquaredToComputesTheRealSquaredDistance() {
        MacroCellPos a = new MacroCellPos(0, 0);
        MacroCellPos b = new MacroCellPos(3, 4);

        assertEquals(25L, a.distanceSquaredTo(b));
    }

    @Test
    void distanceSquaredToHandlesNegativeCoordinates() {
        MacroCellPos a = new MacroCellPos(-2, -2);
        MacroCellPos b = new MacroCellPos(1, 2);

        assertEquals(25L, a.distanceSquaredTo(b));
    }

    @Test
    void equalCellsProduceEqualHashCodes() {
        assertEquals(new MacroCellPos(123, -456).hashCode(), new MacroCellPos(123, -456).hashCode(),
            "the overridden hashCode must still respect the equals/hashCode contract for a record");
    }
}
