package com.mineutze.worldgen.coherence;

import org.junit.jupiter.api.Test;

import static com.mineutze.worldgen.coherence.ClusterSizeModel.Thickness.FLOWER;
import static com.mineutze.worldgen.coherence.ClusterSizeModel.Thickness.NONE;
import static com.mineutze.worldgen.coherence.ClusterSizeModel.Thickness.TRIANGLE;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.A;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.fullyLegalTableWithWidths;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterSizeModelTest {

    private static final double ATOM_WIDTH = 300;
    private static final int TAIL_REACH = 2;
    private static final int FLOWER_FROM = 7;

    private static ClusterSizeModel modelWithSizeRange(int minWidthBlocks, int maxWidthBlocks) {
        return new ClusterSizeModel(fullyLegalTableWithWidths(minWidthBlocks, maxWidthBlocks, A), ATOM_WIDTH, TAIL_REACH, FLOWER_FROM);
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
        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(fullyLegalTableWithWidths(500, 800, A), 0, TAIL_REACH, FLOWER_FROM));
    }

    @Test
    void rejectsATailReachBelowOneBecauseEveryCellOfAThickPartIsItselfWithinOneOfItsCore() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(fullyLegalTableWithWidths(500, 800, A), ATOM_WIDTH, 0, FLOWER_FROM));
    }

    @Test
    void tinyClustersOfOneOrTwoCellsNeedNoThickness() {
        assertEquals(NONE, modelWithSizeRange(500, 600).thicknessRequiredFor(1));
        assertEquals(NONE, modelWithSizeRange(500, 600).thicknessRequiredFor(2));
    }

    @Test
    void clustersOfThreeToSixCellsNeedEveryCellInATriangle() {
        assertEquals(TRIANGLE, modelWithSizeRange(500, 600).thicknessRequiredFor(3));
        assertEquals(TRIANGLE, modelWithSizeRange(500, 600).thicknessRequiredFor(6));
    }

    /** Seven cells is the smallest cluster that can contain a whole flower (a cell plus all six neighbors). */
    @Test
    void clustersOfSevenOrMoreCellsNeedAFlowerCore() {
        assertEquals(FLOWER, modelWithSizeRange(500, 600).thicknessRequiredFor(7));
        assertEquals(FLOWER, modelWithSizeRange(500, 600).thicknessRequiredFor(400));
    }

    /** A larger threshold spares mid-sized clusters the flower and asks only for triangles, a much weaker demand. */
    @Test
    void aLaterFlowerThresholdKeepsTriangleThicknessUntilThen() {
        ClusterSizeModel model = new ClusterSizeModel(fullyLegalTableWithWidths(500, 600, A), ATOM_WIDTH, TAIL_REACH, 13);

        assertEquals(TRIANGLE, model.thicknessRequiredFor(12));
        assertEquals(FLOWER, model.thicknessRequiredFor(13));
    }

    @Test
    void aFlowerThresholdBelowSevenIsRejectedBecauseSmallerClustersCannotHoldAFlower() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(fullyLegalTableWithWidths(500, 600, A), ATOM_WIDTH, TAIL_REACH, 6));
    }

    @Test
    void theModelReportsItsTailReach() {
        assertEquals(TAIL_REACH, modelWithSizeRange(500, 600).tailReachCells());
    }

    @Test
    void aClusterInsideTheRangeWithNoThinCellsHasNoViolation() {
        assertEquals(0, modelWithSizeRange(500, 600).violation(A, 3, 0));
    }

    @Test
    void violationCountsEachCellBelowTheMinimum() {
        assertEquals(2, modelWithSizeRange(500, 600).violation(A, 1, 0));
    }

    @Test
    void violationCountsEachCellAboveTheMaximum() {
        assertEquals(3, modelWithSizeRange(500, 600).violation(A, 7, 0));
    }

    @Test
    void violationCountsEachThinCell() {
        assertEquals(4, modelWithSizeRange(500, 600).violation(A, 3, 4));
    }
}
