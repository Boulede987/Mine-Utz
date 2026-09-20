package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HexTorusGeometryTest {

    private static final double ATOM_WIDTH = 300;
    private static final HexTorusGeometry GEOMETRY = new HexTorusGeometry(6, 6, ATOM_WIDTH);

    @Test
    void aCellCenterBelongsToItsOwnCell() {
        for (ToroidalPos pos : new ToroidalGrid(6, 6).allPositionsInFillOrder()) {
            assertEquals(pos, GEOMETRY.cellAt(GEOMETRY.centerX(pos), GEOMETRY.centerY(pos)), "cell " + pos);
        }
    }

    /** A pointy-top hexagon whose corners are 150 blocks from its center has an inscribed circle of 129.9 blocks: every point inside that circle belongs to the cell. */
    @Test
    void everyPointInsideTheInscribedCircleBelongsToTheCell() {
        ToroidalPos cell = new ToroidalPos(2, 3);
        double inscribedRadius = GEOMETRY.cellWidthBlocks() / 2;

        for (int degrees = 0; degrees < 360; degrees += 10) {
            double x = GEOMETRY.centerX(cell) + 0.99 * inscribedRadius * Math.cos(Math.toRadians(degrees));
            double y = GEOMETRY.centerY(cell) + 0.99 * inscribedRadius * Math.sin(Math.toRadians(degrees));
            assertEquals(cell, GEOMETRY.cellAt(x, y), "angle " + degrees);
        }
    }

    @Test
    void aPointBeyondTheCornerRadiusBelongsToANeighbor() {
        ToroidalPos cell = new ToroidalPos(2, 3);
        double x = GEOMETRY.centerX(cell) + 1.05 * GEOMETRY.cellWidthBlocks() / 2 * 1.0;

        assertEquals(new ToroidalPos(3, 3), GEOMETRY.cellAt(x, GEOMETRY.centerY(cell)));
    }

    /** The world repeats every tile: moving by either tile vector lands in the same cell. */
    @Test
    void thePatternRepeatsAfterEachTileVector() {
        double x = 1234.5;
        double y = 987.6;

        ToroidalPos here = GEOMETRY.cellAt(x, y);

        assertEquals(here, GEOMETRY.cellAt(x + GEOMETRY.tileVectorAX(), y + GEOMETRY.tileVectorAY()));
        assertEquals(here, GEOMETRY.cellAt(x + GEOMETRY.tileVectorBX(), y + GEOMETRY.tileVectorBY()));
        assertEquals(here, GEOMETRY.cellAt(x - 3 * GEOMETRY.tileVectorAX() + 2 * GEOMETRY.tileVectorBX(), y - 3 * GEOMETRY.tileVectorAY() + 2 * GEOMETRY.tileVectorBY()));
    }

    @Test
    void aFortyByFortyTileIsAboutTenThousandBlocksAcross() {
        HexTorusGeometry real = new HexTorusGeometry(40, 40, ATOM_WIDTH);

        assertEquals(10_392, real.tileVectorAX(), 1.0);
        assertEquals(10_392, Math.hypot(real.tileVectorBX(), real.tileVectorBY()), 1.0);
    }

    /** One 300-block-wide hexagon has area 3*sqrt(3)/8 * 300^2 = 58,457 blocks^2, and the tile is exactly cell count times that. */
    @Test
    void cellAndTileAreasAgree() {
        assertEquals(58_457, GEOMETRY.cellAreaBlocks2(), 1.0);
        assertEquals(36 * GEOMETRY.cellAreaBlocks2(), GEOMETRY.tileAreaBlocks2(), 1e-3);
    }

    @Test
    void theAreaOfAHexagonOfAGivenWidthMatchesTheCellArea() {
        assertEquals(GEOMETRY.cellAreaBlocks2(), HexTorusGeometry.areaOfHexagonWidth(ATOM_WIDTH), 1e-6);
    }

    @Test
    void rejectsANonPositiveAtomWidth() {
        assertThrows(IllegalArgumentException.class, () -> new HexTorusGeometry(6, 6, 0));
    }
}
