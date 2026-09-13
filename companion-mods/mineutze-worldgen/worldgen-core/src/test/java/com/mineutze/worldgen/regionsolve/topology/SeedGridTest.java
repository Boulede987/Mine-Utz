package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedGridTest {

    @Test
    void sameQueryTwiceProducesTheSameSeeds() {
        List<Point2D> first = SeedGrid.seedsIn(42L, 400, 0, 0, 4000, 4000);
        List<Point2D> second = SeedGrid.seedsIn(42L, 400, 0, 0, 4000, 4000);
        assertEquals(first, second);
    }

    @Test
    void differentWorldSeedsProduceDifferentSeeds() {
        List<Point2D> a = SeedGrid.seedsIn(1L, 400, 0, 0, 4000, 4000);
        List<Point2D> b = SeedGrid.seedsIn(2L, 400, 0, 0, 4000, 4000);
        assertFalse(a.equals(b));
    }

    /**
     * The whole point of this class: a seed's existence/position must never depend on
     * which window was queried. Querying a big box and a smaller sub-box inside it must
     * agree exactly on every seed the sub-box contains — what makes placement genuinely
     * stateless (no "region" batch unit deciding which window a point belongs to, unlike
     * the deleted SeedPlacer this replaces).
     */
    @Test
    void queryingASubWindowAgreesExactlyWithFilteringTheBiggerWindow() {
        long worldSeed = 7L;
        int spacing = 350;

        List<Point2D> bigWindow = SeedGrid.seedsIn(worldSeed, spacing, -3000, -3000, 3000, 3000);
        Set<Point2D> bigFilteredToSub = new HashSet<>();
        int subMinX = -500;
        int subMinZ = -500;
        int subMaxX = 1200;
        int subMaxZ = 900;
        for (Point2D point : bigWindow) {
            if (point.x() >= subMinX && point.x() < subMaxX && point.z() >= subMinZ && point.z() < subMaxZ) {
                bigFilteredToSub.add(point);
            }
        }

        List<Point2D> subWindow = SeedGrid.seedsIn(worldSeed, spacing, subMinX, subMinZ, subMaxX, subMaxZ);
        assertEquals(bigFilteredToSub, new HashSet<>(subWindow));
    }

    /** Same property as above, checked against a window with a NEGATIVE, non-grid-aligned origin - the grid must be anchored to a fixed global origin (0,0), not to whatever box a caller happens to pass in. */
    @Test
    void windowIndependenceHoldsForOddOffsetsToo() {
        long worldSeed = 123L;
        int spacing = 271;

        List<Point2D> windowA = SeedGrid.seedsIn(worldSeed, spacing, -917, -403, 2183, 1811);
        List<Point2D> windowB = SeedGrid.seedsIn(worldSeed, spacing, 200, 200, 1500, 1500);

        Set<Point2D> windowAFilteredToB = new HashSet<>();
        for (Point2D point : windowA) {
            if (point.x() >= 200 && point.x() < 1500 && point.z() >= 200 && point.z() < 1500) {
                windowAFilteredToB.add(point);
            }
        }
        assertEquals(windowAFilteredToB, new HashSet<>(windowB));
    }

    @Test
    void everySeedLandsStrictlyWithinItsOwnGridCell() {
        int spacing = 500;
        List<Point2D> seeds = SeedGrid.seedsIn(99L, spacing, 0, 0, 5000, 5000);
        assertFalse(seeds.isEmpty());
        for (Point2D seed : seeds) {
            int cellX = Math.floorDiv((int) seed.x(), spacing) * spacing;
            int cellZ = Math.floorDiv((int) seed.z(), spacing) * spacing;
            assertTrue(seed.x() >= cellX && seed.x() < cellX + spacing, "seed " + seed + " escaped its own cell on x");
            assertTrue(seed.z() >= cellZ && seed.z() < cellZ + spacing, "seed " + seed + " escaped its own cell on z");
        }
    }

    @Test
    void everySeedLiesWithinTheRequestedBox() {
        List<Point2D> seeds = SeedGrid.seedsIn(5L, 300, 100, 100, 2000, 1700);
        for (Point2D seed : seeds) {
            assertTrue(seed.x() >= 100 && seed.x() < 2000);
            assertTrue(seed.z() >= 100 && seed.z() < 1700);
        }
    }

    @Test
    void atMostOneSeedPerGridCell() {
        int spacing = 400;
        List<Point2D> seeds = SeedGrid.seedsIn(11L, spacing, 0, 0, 8000, 8000);
        Set<String> cellsSeen = new HashSet<>();
        for (Point2D seed : seeds) {
            int cellX = Math.floorDiv((int) seed.x(), spacing);
            int cellZ = Math.floorDiv((int) seed.z(), spacing);
            assertTrue(cellsSeen.add(cellX + "," + cellZ), "cell (" + cellX + "," + cellZ + ") produced more than one seed");
        }
    }

    @Test
    void rejectsNonPositiveSpacing() {
        assertThrows(IllegalArgumentException.class, () -> SeedGrid.seedsIn(1L, 0, 0, 0, 100, 100));
    }

    @Test
    void rejectsEmptyOrInvertedBox() {
        assertThrows(IllegalArgumentException.class, () -> SeedGrid.seedsIn(1L, 100, 0, 0, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> SeedGrid.seedsIn(1L, 100, 100, 0, 0, 100));
    }
}
