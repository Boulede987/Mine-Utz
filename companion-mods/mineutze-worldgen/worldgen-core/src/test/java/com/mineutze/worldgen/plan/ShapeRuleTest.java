package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static com.mineutze.worldgen.plan.ShapeRule.NO_PERIMETER_FLOOR;
import static com.mineutze.worldgen.plan.ShapeRule.NO_PERIMETER_LIMIT;
import static com.mineutze.worldgen.plan.ShapeRule.Thickness.FLOWER;
import static com.mineutze.worldgen.plan.ShapeRule.Thickness.NONE;
import static com.mineutze.worldgen.plan.ShapeRule.Thickness.TRIANGLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeRuleTest {

    private static ShapeRule rule(double perimeterRatio, int triangleFrom, int flowerFrom) {
        return new ShapeRule(perimeterRatio, triangleFrom, flowerFrom, 2);
    }

    @Test
    void clustersSmallerThanTheTriangleThresholdNeedNoThickness() {
        ShapeRule rule = rule(NO_PERIMETER_LIMIT, 3, 13);

        assertEquals(NONE, rule.thicknessRequiredFor(1));
        assertEquals(NONE, rule.thicknessRequiredFor(2));
    }

    @Test
    void clustersFromTheTriangleThresholdUntilTheFlowerThresholdNeedEveryCellInATriangle() {
        ShapeRule rule = rule(NO_PERIMETER_LIMIT, 3, 13);

        assertEquals(TRIANGLE, rule.thicknessRequiredFor(3));
        assertEquals(TRIANGLE, rule.thicknessRequiredFor(12));
    }

    @Test
    void clustersFromTheFlowerThresholdNeedAFlowerCore() {
        ShapeRule rule = rule(NO_PERIMETER_LIMIT, 3, 13);

        assertEquals(FLOWER, rule.thicknessRequiredFor(13));
        assertEquals(FLOWER, rule.thicknessRequiredFor(400));
    }

    @Test
    void aLaterTriangleThresholdLeavesMoreSmallClustersUnchecked() {
        ShapeRule rule = rule(NO_PERIMETER_LIMIT, 6, 13);

        assertEquals(NONE, rule.thicknessRequiredFor(5));
        assertEquals(TRIANGLE, rule.thicknessRequiredFor(6));
    }

    @Test
    void thresholdsAtIntegerMaxSwitchAThicknessTierOffCompletely() {
        ShapeRule rule = rule(NO_PERIMETER_LIMIT, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertEquals(NONE, rule.thicknessRequiredFor(25));
    }

    /** Known minimum perimeters of hexagon clusters: one cell 6, two 10, three 12, the seven-cell flower 18. */
    @Test
    void minimumPerimeterMatchesKnownHexagonalClusters() {
        assertEquals(6, ShapeRule.minimumPerimeter(1));
        assertEquals(10, ShapeRule.minimumPerimeter(2));
        assertEquals(12, ShapeRule.minimumPerimeter(3));
        assertEquals(18, ShapeRule.minimumPerimeter(7));
    }

    /** Three cells have a minimum perimeter of 12; a 1.25 ratio tolerates up to 15. */
    @Test
    void thePerimeterRatioGivesSlackAboveTheMinimum() {
        assertEquals(15, rule(1.25, 3, 13).allowedPerimeter(3));
    }

    @Test
    void aRatioOfOneAllowsExactlyTheMinimumPerimeter() {
        ShapeRule rule = rule(1.0, 3, 13);

        for (int cellCount = 1; cellCount <= 60; cellCount++) {
            assertEquals(ShapeRule.minimumPerimeter(cellCount), rule.allowedPerimeter(cellCount), "cell count " + cellCount);
        }
    }

    @Test
    void theMostCompactClusterIsAlwaysAllowedWhateverTheRatio() {
        ShapeRule rule = rule(1.6, 3, 13);

        for (int cellCount = 1; cellCount <= 60; cellCount++) {
            assertTrue(rule.allowedPerimeter(cellCount) >= ShapeRule.minimumPerimeter(cellCount), "cell count " + cellCount);
        }
    }

    @Test
    void noPerimeterLimitAllowsAnyPerimeter() {
        assertEquals(Integer.MAX_VALUE, rule(NO_PERIMETER_LIMIT, 3, 13).allowedPerimeter(20));
    }

    /** No floor set (the common 4-arg constructor) returns 0 whatever the cell count - even where the flat-plane minimum itself is large - so it never falsely fires on a cluster that wraps the torus around another cluster's hole. */
    @Test
    void noPerimeterFloorAlwaysReturnsZero() {
        ShapeRule rule = rule(NO_PERIMETER_LIMIT, 3, 13);

        for (int cellCount : new int[] {1, 3, 7, 61}) {
            assertEquals(0, rule.requiredMinimumPerimeter(cellCount), "cell count " + cellCount);
        }
    }

    /** Three cells have a minimum perimeter of 12; a 2.0 floor demands at least 24 - real elongation, not just permission. */
    @Test
    void aPerimeterFloorDemandsMoreThanTheGeometricMinimum() {
        ShapeRule rule = new ShapeRule(NO_PERIMETER_LIMIT, 2.0, 3, 13, 2);

        assertEquals(24, rule.requiredMinimumPerimeter(3));
    }

    @Test
    void rejectsAPerimeterRatioBelowOneBecauseNoShapeIsMoreCompactThanTheMinimum() {
        assertThrows(IllegalArgumentException.class, () -> rule(0.9, 3, 13));
    }

    @Test
    void rejectsAMinPerimeterRatioBelowOneBecauseAClusterCanNeverHaveLessThanItsGeometricMinimum() {
        assertThrows(IllegalArgumentException.class, () -> new ShapeRule(NO_PERIMETER_LIMIT, 0.9, 3, 13, 2));
    }

    @Test
    void rejectsAFloorStricterThanTheCeiling() {
        assertThrows(IllegalArgumentException.class, () -> new ShapeRule(1.5, 2.0, 3, 13, 2));
    }

    @Test
    void aFloorEqualToTheCeilingIsAccepted() {
        ShapeRule rule = new ShapeRule(1.5, 1.5, 3, 13, 2);

        assertEquals(rule.allowedPerimeter(10), rule.requiredMinimumPerimeter(10));
    }

    @Test
    void rejectsATriangleThresholdBelowThreeBecauseSmallerClustersCannotHoldATriangle() {
        assertThrows(IllegalArgumentException.class, () -> rule(1.5, 2, 13));
    }

    @Test
    void rejectsAFlowerThresholdBelowSevenBecauseSmallerClustersCannotHoldAFlower() {
        assertThrows(IllegalArgumentException.class, () -> rule(1.5, 3, 6));
    }

    @Test
    void rejectsAFlowerThresholdBelowTheTriangleThreshold() {
        assertThrows(IllegalArgumentException.class, () -> rule(1.5, 9, 8));
    }

    @Test
    void rejectsATailReachBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new ShapeRule(1.5, 3, 13, 0));
    }
}
