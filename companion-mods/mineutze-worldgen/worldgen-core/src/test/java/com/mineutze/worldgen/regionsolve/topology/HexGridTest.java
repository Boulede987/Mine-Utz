package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexGridTest {

    @Test
    void sameQueryTwiceProducesTheSamePositions() {
        List<HexGrid.AxialPos> first = HexGrid.axialPositionsIn(200, 0, 0, 4000, 4000);
        List<HexGrid.AxialPos> second = HexGrid.axialPositionsIn(200, 0, 0, 4000, 4000);
        assertEquals(first, second);
    }

    /**
     * Unlike {@link SeedGrid}, no world seed is involved at all - this is a pure
     * closed-form regular lattice, so two overlapping windows must agree EXACTLY, not
     * just statistically.
     */
    @Test
    void queryingASubWindowAgreesExactlyWithFilteringTheBiggerWindow() {
        double size = 250;

        List<HexGrid.AxialPos> bigWindow = HexGrid.axialPositionsIn(size, -3000, -3000, 3000, 3000);
        int subMinX = -500;
        int subMinZ = -500;
        int subMaxX = 1200;
        int subMaxZ = 900;

        Set<Point2D> bigFilteredToSub = new HashSet<>();
        for (HexGrid.AxialPos pos : bigWindow) {
            Point2D center = HexGrid.centerOf(pos, size);
            if (center.x() >= subMinX && center.x() < subMaxX && center.z() >= subMinZ && center.z() < subMaxZ) {
                bigFilteredToSub.add(center);
            }
        }

        List<HexGrid.AxialPos> subWindow = HexGrid.axialPositionsIn(size, subMinX, subMinZ, subMaxX, subMaxZ);
        Set<Point2D> subCenters = new HashSet<>();
        for (HexGrid.AxialPos pos : subWindow) {
            subCenters.add(HexGrid.centerOf(pos, size));
        }

        assertEquals(bigFilteredToSub, subCenters);
    }

    @Test
    void everyCenterLiesWithinTheRequestedBox() {
        double size = 180;
        List<HexGrid.AxialPos> positions = HexGrid.axialPositionsIn(size, 100, 100, 3000, 2500);
        assertFalse(positions.isEmpty());
        for (HexGrid.AxialPos pos : positions) {
            Point2D center = HexGrid.centerOf(pos, size);
            assertTrue(center.x() >= 100 && center.x() < 3000);
            assertTrue(center.z() >= 100 && center.z() < 2500);
        }
    }

    @Test
    void noTwoAxialPositionsProduceTheSameCenter() {
        double size = 220;
        List<HexGrid.AxialPos> positions = HexGrid.axialPositionsIn(size, 0, 0, 5000, 5000);
        Set<Point2D> centers = new HashSet<>();
        for (HexGrid.AxialPos pos : positions) {
            assertTrue(centers.add(HexGrid.centerOf(pos, size)), "axial position " + pos + " collided with another one's center");
        }
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> HexGrid.axialPositionsIn(0, 0, 0, 100, 100));
    }

    @Test
    void rejectsEmptyOrInvertedBox() {
        assertThrows(IllegalArgumentException.class, () -> HexGrid.axialPositionsIn(100, 0, 0, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> HexGrid.axialPositionsIn(100, 100, 0, 0, 100));
    }

    @Test
    void nearestAxialPosRoundTripsExactlyOnARealCenter() {
        double size = 250;
        for (HexGrid.AxialPos pos : HexGrid.axialPositionsIn(size, -2000, -2000, 2000, 2000)) {
            Point2D center = HexGrid.centerOf(pos, size);
            assertEquals(pos, HexGrid.nearestAxialPos(center, size), "center of " + pos + " didn't round-trip");
        }
    }

    /** A point nudged a small fraction of the way toward one specific neighbor must resolve to THAT neighbor, not drift to some other one. */
    @Test
    void nearestAxialPosResolvesToTheCorrectNeighborWhenNudgedTowardIt() {
        double size = 250;
        HexGrid.AxialPos origin = new HexGrid.AxialPos(0, 0);
        HexGrid.AxialPos neighbor = new HexGrid.AxialPos(1, 0);
        Point2D originCenter = HexGrid.centerOf(origin, size);
        Point2D neighborCenter = HexGrid.centerOf(neighbor, size);

        Point2D nudgedTowardNeighbor = new Point2D(
            originCenter.x() + 0.9 * (neighborCenter.x() - originCenter.x()),
            originCenter.z() + 0.9 * (neighborCenter.z() - originCenter.z())
        );
        assertEquals(neighbor, HexGrid.nearestAxialPos(nudgedTowardNeighbor, size));

        Point2D nudgedTowardOrigin = new Point2D(
            originCenter.x() + 0.1 * (neighborCenter.x() - originCenter.x()),
            originCenter.z() + 0.1 * (neighborCenter.z() - originCenter.z())
        );
        assertEquals(origin, HexGrid.nearestAxialPos(nudgedTowardOrigin, size));
    }

    /** Every real lattice center, checked directly - {@code nearestAxialPos} of a point exactly HALFWAY between a center and any of its own hex corners must still resolve to that same center (the corner is, by construction, closer to the center than to any other lattice point). */
    @Test
    void nearestAxialPosIsCorrectAcrossARealLatticeNotJustOneHandPickedCase() {
        double size = 180;
        List<HexGrid.AxialPos> positions = HexGrid.axialPositionsIn(size, -3000, -3000, 3000, 3000);
        for (HexGrid.AxialPos pos : positions) {
            Point2D center = HexGrid.centerOf(pos, size);
            for (int corner = 0; corner < 6; corner++) {
                double angle = Math.toRadians(60.0 * corner - 30.0);
                Point2D nearCenter = new Point2D(
                    center.x() + 0.1 * size * Math.cos(angle),
                    center.z() + 0.1 * size * Math.sin(angle)
                );
                assertEquals(pos, HexGrid.nearestAxialPos(nearCenter, size), "a point 10% of the way toward corner " + corner + " of " + pos + " resolved to the wrong face");
            }
        }
    }
}
