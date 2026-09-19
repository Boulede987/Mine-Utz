package com.mineutze.worldgen.coherence;

import org.junit.jupiter.api.Test;

import static com.mineutze.worldgen.coherence.ToroidalTestTables.A;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.fullyLegalTableWithWidths;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterSizeModelTest {

    private static final double ATOM_WIDTH = 300;
    private static final double ROUNDNESS_RATIO = 1.25;

    private static ClusterSizeModel modelWithSizeRange(int minWidthBlocks, int maxWidthBlocks) {
        return new ClusterSizeModel(fullyLegalTableWithWidths(minWidthBlocks, maxWidthBlocks, A), ATOM_WIDTH, ROUNDNESS_RATIO);
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
        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(fullyLegalTableWithWidths(500, 800, A), 0, ROUNDNESS_RATIO));
    }

    @Test
    void rejectsARoundnessRatioBelowOneBecauseNoShapeIsMoreCompactThanTheMinimum() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(fullyLegalTableWithWidths(500, 800, A), ATOM_WIDTH, 0.9));
    }

    /** Known minimum perimeters of hexagon clusters: one cell 6, two 10, three 12, the seven-cell flower 18. */
    @Test
    void minimumPerimeterMatchesKnownHexagonalClusters() {
        assertEquals(6, ClusterSizeModel.minimumPerimeter(1));
        assertEquals(10, ClusterSizeModel.minimumPerimeter(2));
        assertEquals(12, ClusterSizeModel.minimumPerimeter(3));
        assertEquals(18, ClusterSizeModel.minimumPerimeter(7));
    }

    @Test
    void theMostCompactClusterIsAlwaysAllowed() {
        ClusterSizeModel model = modelWithSizeRange(300, 700);

        for (int cellCount = 1; cellCount <= 60; cellCount++) {
            assertTrue(model.allowedPerimeter(cellCount) >= ClusterSizeModel.minimumPerimeter(cellCount), "cell count " + cellCount);
        }
    }

    /** Three cells have a minimum perimeter of 12; a 1.25 ratio tolerates up to 15 before a cluster stops counting as round. */
    @Test
    void theRoundnessRatioGivesSlackAboveTheMinimumPerimeter() {
        ClusterSizeModel model = modelWithSizeRange(500, 600);

        assertEquals(15, model.allowedPerimeter(3));
        assertEquals(0, model.violation(A, 3, 15), "15 is within 25% of the minimum 12");
        assertEquals(1, model.violation(A, 3, 16), "16 is one edge too ragged");
    }

    @Test
    void aClusterInsideTheRangeAndWithinTheAllowedPerimeterHasNoViolation() {
        ClusterSizeModel model = modelWithSizeRange(500, 600);

        assertEquals(0, model.violation(A, 3, ClusterSizeModel.minimumPerimeter(3)));
    }

    @Test
    void violationCountsEachCellBelowTheMinimum() {
        ClusterSizeModel model = modelWithSizeRange(500, 600);

        assertEquals(2, model.violation(A, 1, ClusterSizeModel.minimumPerimeter(1)));
    }

    @Test
    void violationCountsEachCellAboveTheMaximum() {
        ClusterSizeModel model = modelWithSizeRange(500, 600);

        assertEquals(3, model.violation(A, 7, ClusterSizeModel.minimumPerimeter(7)));
    }

    @Test
    void violationCountsEachPerimeterEdgeBeyondWhatRoundnessAllows() {
        ClusterSizeModel model = modelWithSizeRange(500, 600);
        int allowed = model.allowedPerimeter(3);

        assertEquals(2, model.violation(A, 3, allowed + 2));
    }
}
