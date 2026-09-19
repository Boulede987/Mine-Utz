package com.mineutze.worldgen.coherence;

/**
 * What shape a cluster of same-biome cells may take (2026-09-19). Biomes may be irregular -
 * natural, not round - but not thin snakes (user). Two independent checks, either can be
 * switched off:
 *
 * <p><b>Perimeter</b>: a cluster's perimeter (cell-edges touching a different biome) may not
 * exceed {@code perimeterRatio} times the smallest perimeter any cluster of that many
 * hexagons can have, {@code 2 * ceil(sqrt(12n - 3))} (Harary and Harborth, hexagonal animals).
 * Ratio 1 demands the most compact shape; {@link #NO_PERIMETER_LIMIT} allows any. Snakes have a
 * large perimeter for their area, so a loose ratio still forbids them without demanding round.
 *
 * <p><b>Thickness</b> ({@link Thickness}): from {@code smallestClusterNeedingATriangle} cells
 * every cell must sit in a triangle of three mutually adjacent cluster cells (two cells across);
 * from {@code smallestClusterNeedingAFlower} cells the cluster must contain flowers (a cell plus
 * its six neighbors, three across) and every cell must lie within {@code tailReachCells} steps of
 * a flower's center, so bumps and short necks are fine but a long thin arm is not. Passing
 * {@link Integer#MAX_VALUE} for a threshold switches that tier off.
 */
record ShapeRule(double perimeterRatio, int smallestClusterNeedingATriangle, int smallestClusterNeedingAFlower, int tailReachCells) {

    /** The kind of solid piece every cell of a cluster must belong to, chosen by the cluster's size. */
    enum Thickness {
        /** Nothing to check. */
        NONE,
        /** Every cell must be part of a triangle of three mutually adjacent cluster cells. */
        TRIANGLE,
        /** The cluster must contain flowers, and every cell must be within the tail reach of a flower's center. */
        FLOWER
    }

    static final double NO_PERIMETER_LIMIT = Double.POSITIVE_INFINITY;

    private static final int SMALLEST_CLUSTER_ABLE_TO_HOLD_A_TRIANGLE = 3;
    private static final int SMALLEST_CLUSTER_ABLE_TO_HOLD_A_FLOWER = 7;
    private static final double ROUNDING_TOLERANCE = 1e-9;

    ShapeRule {
        if (perimeterRatio < 1.0) {
            throw new IllegalArgumentException("ShapeRule: perimeterRatio must be at least 1 (no shape is more compact than the minimum), got " + perimeterRatio);
        }
        if (smallestClusterNeedingATriangle < SMALLEST_CLUSTER_ABLE_TO_HOLD_A_TRIANGLE) {
            throw new IllegalArgumentException(
                "ShapeRule: smallestClusterNeedingATriangle must be at least " + SMALLEST_CLUSTER_ABLE_TO_HOLD_A_TRIANGLE + " (a smaller cluster cannot hold a triangle), got " + smallestClusterNeedingATriangle
            );
        }
        if (smallestClusterNeedingAFlower < SMALLEST_CLUSTER_ABLE_TO_HOLD_A_FLOWER) {
            throw new IllegalArgumentException(
                "ShapeRule: smallestClusterNeedingAFlower must be at least " + SMALLEST_CLUSTER_ABLE_TO_HOLD_A_FLOWER + " (a smaller cluster cannot hold a flower), got " + smallestClusterNeedingAFlower
            );
        }
        if (smallestClusterNeedingAFlower < smallestClusterNeedingATriangle) {
            throw new IllegalArgumentException(
                "ShapeRule: the flower threshold " + smallestClusterNeedingAFlower + " is below the triangle threshold " + smallestClusterNeedingATriangle + " - the flower tier would replace the triangle tier before it began"
            );
        }
        if (tailReachCells < 1) {
            throw new IllegalArgumentException("ShapeRule: tailReachCells must be at least 1 (a flower's own rim is one step from its center), got " + tailReachCells);
        }
    }

    Thickness thicknessRequiredFor(int cellCount) {
        if (cellCount >= smallestClusterNeedingAFlower) {
            return Thickness.FLOWER;
        }
        return cellCount >= smallestClusterNeedingATriangle ? Thickness.TRIANGLE : Thickness.NONE;
    }

    /** Largest perimeter, in cell-edges, a cluster of {@code cellCount} cells may have. */
    int allowedPerimeter(int cellCount) {
        if (perimeterRatio == NO_PERIMETER_LIMIT) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(perimeterRatio * minimumPerimeter(cellCount) - ROUNDING_TOLERANCE);
    }

    /** Smallest possible perimeter of a hexagon cluster of {@code cellCount} cells: 6, 10, 12, ... 18 for the seven-cell flower. */
    static int minimumPerimeter(int cellCount) {
        int target = 12 * cellCount - 3;
        int root = (int) Math.ceil(Math.sqrt(target));
        while (root * root < target) {
            root++;
        }
        while ((root - 1) * (root - 1) >= target) {
            root--;
        }
        return 2 * root;
    }
}
