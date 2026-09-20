package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableRequiring;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirement;
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

        assertEquals(0, model.violation(A, 3, 0, ShapeRule.minimumPerimeter(3), 0));
    }

    @Test
    void violationCountsEachCellBelowTheMinimum() {
        assertEquals(2, modelWithSizeRange(500, 600).violation(A, 1, 0, 6, 0));
    }

    @Test
    void violationCountsEachCellAboveTheMaximum() {
        assertEquals(3, modelWithSizeRange(500, 600).violation(A, 7, 0, 18, 0));
    }

    @Test
    void violationCountsEachThinCell() {
        assertEquals(4, modelWithSizeRange(500, 600).violation(A, 3, 4, 12, 0));
    }

    @Test
    void violationCountsEachPerimeterEdgeBeyondWhatTheRuleAllows() {
        ClusterSizeModel model = modelWithSizeRange(500, 600, PERIMETER_RULE);
        int allowed = PERIMETER_RULE.allowedPerimeter(3);

        assertEquals(2, model.violation(A, 3, 0, allowed + 2, 0));
    }

    @Test
    void aRuleWithoutAPerimeterLimitNeverChargesForPerimeter() {
        assertEquals(0, modelWithSizeRange(500, 600).violation(A, 3, 0, 1_000, 0));
    }

    @Test
    void aBorderShortfallAddsItsEdgesToTheViolation() {
        assertEquals(5, modelWithSizeRange(500, 600).violation(A, 3, 0, ShapeRule.minimumPerimeter(3), 5));
    }

    @Test
    void theModelExposesEachBiomesRequiredBorders() {
        ClusterSizeModel model = new ClusterSizeModel(fullyLegalTableRequiring(500, 800, A, B, 4, A, B, C), ATOM_WIDTH, NO_PERIMETER_RULE);

        assertTrue(model.hasRequirements(0));
        assertEquals(java.util.List.of(1), java.util.Arrays.stream(model.requiredTargets(0)).boxed().toList());
        assertEquals(java.util.List.of(4), java.util.Arrays.stream(model.requiredMinEdges(0)).boxed().toList());
        assertEquals(0, model.requiredTargets(1).length);
    }

    @Test
    void aRequirementOnABiomeNotInTheTableIsRejectedNamingBoth() {
        var catalog = withRequirement(ToroidalTestTables.fullyLegalCatalog(500, 800, A, B), A, C, 2);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(RawRuleTable.from(catalog), ATOM_WIDTH, NO_PERIMETER_RULE));

        assertTrue(failure.getMessage().contains(A.toString()) && failure.getMessage().contains(C.toString()), failure.getMessage());
    }

    @Test
    void aRequirementOnABiomeThatMayNeverTouchItIsRejected() {
        var catalog = withRequirement(noLegalTriangleCatalog(500, 800), A, C, 2);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(RawRuleTable.from(catalog), ATOM_WIDTH, NO_PERIMETER_RULE));

        assertTrue(failure.getMessage().contains("not a legal neighbor"), failure.getMessage());
    }

    @Test
    void aRequirementOnItselfIsRejected() {
        var catalog = withRequirement(ToroidalTestTables.fullyLegalCatalog(500, 800, A, B), A, A, 2);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(RawRuleTable.from(catalog), ATOM_WIDTH, NO_PERIMETER_RULE));

        assertTrue(failure.getMessage().contains("itself"), failure.getMessage());
    }

    @Test
    void aNonPositiveRequirementIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(fullyLegalTableRequiring(500, 800, A, B, 0, A, B), ATOM_WIDTH, NO_PERIMETER_RULE));
    }

    /** A biome of at most 5 cells has at most 22 perimeter edges (a straight line), and a perimeter rule may allow fewer: asking for more shared edges than that can never be met. */
    @Test
    void aRequirementLargerThanTheLargestPerimeterTheShapeAllowsIsRejected() {
        RawRuleTable table = fullyLegalTableRequiring(300, 700, A, B, 22, A, B);

        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(table, ATOM_WIDTH, PERIMETER_RULE));
    }

    /** A straight line of 5 hexagons has 4 * 5 + 2 = 22 outline edges, the most any 5-cell cluster can have. */
    @Test
    void aRequirementOfAStraightLinesWholeOutlineIsAcceptedWhenTheShapeRuleAllowsIt() {
        RawRuleTable table = fullyLegalTableRequiring(300, 700, A, B, 22, A, B);

        new ClusterSizeModel(table, ATOM_WIDTH, NO_PERIMETER_RULE);
    }

    @Test
    void aRequirementBeyondAStraightLinesOutlineIsRejectedEvenWithoutAPerimeterRule() {
        RawRuleTable table = fullyLegalTableRequiring(300, 700, A, B, 23, A, B);

        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(table, ATOM_WIDTH, NO_PERIMETER_RULE));
    }

    @Test
    void aRequirementWithinTheLargestAllowedPerimeterIsAccepted() {
        RawRuleTable table = fullyLegalTableRequiring(300, 700, A, B, ShapeRule.minimumPerimeter(5), A, B);

        new ClusterSizeModel(table, ATOM_WIDTH, PERIMETER_RULE);
    }
}
