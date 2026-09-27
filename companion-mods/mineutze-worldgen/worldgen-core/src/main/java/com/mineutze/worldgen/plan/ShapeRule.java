package com.mineutze.worldgen.plan;

/**
 * What shape a cluster of same-biome cells may take (2026-09-19, {@code minPerimeterRatio}
 * 2026-09-26). Biomes may be irregular - natural, not round - but not thin snakes (user), UNLESS
 * a biome's own profile asks for the opposite (a beach hugging a coastline). Three independent
 * checks, each can be switched off:
 *
 * <p><b>Perimeter ceiling</b>: a cluster's perimeter (cell-edges touching a different biome) may
 * not exceed {@code perimeterRatio} times the smallest perimeter any cluster of that many
 * hexagons can have, {@code 2 * ceil(sqrt(12n - 3))} (Harary and Harborth, hexagonal animals).
 * Ratio 1 demands the most compact shape; {@link #NO_PERIMETER_LIMIT} allows any. Snakes have a
 * large perimeter for their area, so a loose ratio still forbids them without demanding round.
 *
 * <p><b>Perimeter floor</b>: the mirror image - a cluster's perimeter must be AT LEAST {@code
 * minPerimeterRatio} times that same minimum, forcing elongation instead of merely permitting it.
 * {@link #NO_PERIMETER_FLOOR} (1, the trivial floor every cluster already meets) switches this
 * off. Only permission (a loose ceiling) never produced an elongated shape in practice - nothing
 * in the search's cost rewards spending extra perimeter, so a real requirement needs a real floor.
 *
 * <p><b>Thickness</b> ({@link Thickness}): from {@code smallestClusterNeedingATriangle} cells
 * every cell must sit in a triangle of three mutually adjacent cluster cells (two cells across);
 * from {@code smallestClusterNeedingAFlower} cells the cluster must contain flowers (a cell plus
 * its six neighbors, three across) and every cell must lie within {@code tailReachCells} steps of
 * a flower's center, so bumps and short necks are fine but a long thin arm is not. Passing
 * {@link Integer#MAX_VALUE} for a threshold switches that tier off. A thin-shape profile switches
 * this tier off entirely (a long arm is the point, not a defect) while relying on the perimeter
 * floor to force the elongation in the first place.
 */
record ShapeRule(double perimeterRatio, double minPerimeterRatio, int smallestClusterNeedingATriangle, int smallestClusterNeedingAFlower, int tailReachCells) {

    /** Convenience constructor for the common case: no perimeter floor, just the existing ceiling and thickness checks. */
    ShapeRule(double perimeterRatio, int smallestClusterNeedingATriangle, int smallestClusterNeedingAFlower, int tailReachCells) {
        this(perimeterRatio, NO_PERIMETER_FLOOR, smallestClusterNeedingATriangle, smallestClusterNeedingAFlower, tailReachCells);
    }

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
    static final double NO_PERIMETER_FLOOR = 1.0;

    private static final int SMALLEST_CLUSTER_ABLE_TO_HOLD_A_TRIANGLE = 3;
    private static final int SMALLEST_CLUSTER_ABLE_TO_HOLD_A_FLOWER = 7;
    private static final double ROUNDING_TOLERANCE = 1e-9;

    ShapeRule {
        if (perimeterRatio < 1.0) {
            throw new IllegalArgumentException("ShapeRule: perimeterRatio must be at least 1 (no shape is more compact than the minimum), got " + perimeterRatio);
        }
        if (minPerimeterRatio < NO_PERIMETER_FLOOR) {
            throw new IllegalArgumentException("ShapeRule: minPerimeterRatio must be at least 1 (a cluster can never have less than its geometric minimum perimeter), got " + minPerimeterRatio);
        }
        if (perimeterRatio != NO_PERIMETER_LIMIT && minPerimeterRatio > perimeterRatio) {
            throw new IllegalArgumentException("ShapeRule: minPerimeterRatio " + minPerimeterRatio + " exceeds perimeterRatio " + perimeterRatio + " - the floor would be stricter than the ceiling");
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

    /**
     * Smallest perimeter, in cell-edges, a cluster of {@code cellCount} cells must have - the mirror of {@link #allowedPerimeter}, forcing elongation instead of
     * merely permitting it. {@link #NO_PERIMETER_FLOOR} returns 0 (always met) rather than {@code minimumPerimeter(cellCount)} itself: that flat-plane formula is
     * only a true lower bound for an isolated blob, not for a cluster that wraps the toroidal grid around another cluster's hole (whose real perimeter can be far
     * smaller than its own cell count would suggest) - the floor must stay an explicit opt-in, never an accidental side effect of measuring cell count at all.
     */
    int requiredMinimumPerimeter(int cellCount) {
        if (minPerimeterRatio == NO_PERIMETER_FLOOR) {
            return 0;
        }
        return (int) Math.floor(minPerimeterRatio * minimumPerimeter(cellCount) + ROUNDING_TOLERANCE);
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
