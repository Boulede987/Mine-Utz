package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterSizeModelTest {

    private static final double ATOM_WIDTH = 300;
    private static final ShapeRule NO_PERIMETER_RULE = new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, 3, 13, 2);
    private static final ShapeRule PERIMETER_RULE = new ShapeRule(1.25, 3, 13, 2);

    private static ClusterSizeModel modelWithSizeRange(int minWidthBlocks, int maxWidthBlocks) {
        return modelWithSizeRange(minWidthBlocks, maxWidthBlocks, NO_PERIMETER_RULE);
    }

    private static ClusterSizeModel modelWithSizeRange(int minWidthBlocks, int maxWidthBlocks, ShapeRule shape) {
        return new ClusterSizeModel(fullyLegalTableWithWidths(minWidthBlocks, maxWidthBlocks, A), ATOM_WIDTH, shape);
    }

    @Test
    void aBiomeExactlyOneCellWideNeedsExactlyOneCellAtMinimum() {
        assertEquals(1, modelWithSizeRange(300, 700).minCells(A));
    }

    /** Size is an area, so cells scale with the SQUARE of the width ratio: (500/300)^2 = 2.78 cells, and a fraction of a cell cannot be built, so the minimum rounds up. */
    @Test
    void minimumCellsRoundUpBecauseSizeScalesWithTheSquareOfTheWidth() {
        assertEquals(3, modelWithSizeRange(500, 600).minCells(A));
    }

    @Test
    void maximumCellsRoundDown() {
        assertEquals(5, modelWithSizeRange(300, 700).maxCells(A));
    }

    @Test
    void anExactAreaRatioIsNotPushedToTheNextCellByRoundingNoise() {
        ClusterSizeModel model = modelWithSizeRange(600, 600);

        assertEquals(4, model.minCells(A));
        assertEquals(4, model.maxCells(A));
    }

    @Test
    void rejectsASizeRangeThatContainsNoWholeNumberOfCells() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> modelWithSizeRange(320, 330));

        assertTrue(failure.getMessage().contains(A.toString()), "message should name the biome: " + failure.getMessage());
    }

    @Test
    void rejectsANonPositiveCellWidth() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(fullyLegalTableWithWidths(500, 800, A), 0, NO_PERIMETER_RULE));
    }

    @Test
    void theModelExposesItsShapeRule() {
        assertEquals(PERIMETER_RULE, modelWithSizeRange(500, 600, PERIMETER_RULE).shape());
    }

    @Test
    void aClusterInsideTheRangeWithNoThinCellsAndAnAllowedPerimeterHasNoViolation() {
        ClusterSizeModel model = modelWithSizeRange(500, 600, PERIMETER_RULE);

        assertEquals(0, model.violation(A, 3, 0, ShapeRule.minimumPerimeter(3)));
    }

    @Test
    void violationCountsEachCellBelowTheMinimum() {
        assertEquals(2, modelWithSizeRange(500, 600).violation(A, 1, 0, 6));
    }

    @Test
    void violationCountsEachCellAboveTheMaximum() {
        assertEquals(3, modelWithSizeRange(500, 600).violation(A, 7, 0, 18));
    }

    @Test
    void violationCountsEachThinCell() {
        assertEquals(4, modelWithSizeRange(500, 600).violation(A, 3, 4, 12));
    }

    @Test
    void violationCountsEachPerimeterEdgeBeyondWhatTheRuleAllows() {
        ClusterSizeModel model = modelWithSizeRange(500, 600, PERIMETER_RULE);
        int allowed = PERIMETER_RULE.allowedPerimeter(3);

        assertEquals(2, model.violation(A, 3, 0, allowed + 2));
    }

    @Test
    void aRuleWithoutAPerimeterLimitNeverChargesForPerimeter() {
        assertEquals(0, modelWithSizeRange(500, 600).violation(A, 3, 0, 1_000));
    }
}
