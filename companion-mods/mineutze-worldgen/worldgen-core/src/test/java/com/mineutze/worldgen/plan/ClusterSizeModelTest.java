package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import org.junit.jupiter.api.Test;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.D;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableRequiring;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withGroup;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirement;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirementText;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static ClusterSizeModel modelOf(String requirementOfA, BiomeGraphData base) {
        return new ClusterSizeModel(RawRuleTable.from(withRequirementText(base, A, requirementOfA)), ATOM_WIDTH, NO_PERIMETER_RULE);
    }

    private static BiomeGraphData fourBiomes() {
        return withGroup(withGroup(ToroidalTestTables.fullyLegalCatalog(500, 800, A, B, C, D), "bc", B, C), "cd", C, D);
    }

    @Test
    void aBiomeWithARequirementSaysSoAndOthersDoNot() {
        ClusterSizeModel model = modelOf("test:b >= 4", fourBiomes());

        assertTrue(model.hasRequirement(0));
        assertFalse(model.hasRequirement(1));
    }

    @Test
    void theShortfallOfAShareIsTheEdgesStillMissing() {
        ClusterSizeModel model = modelOf("test:b >= 4", fourBiomes());

        assertEquals(1, model.borderShortfall(0, new int[] {0, 3, 0, 0}));
        assertEquals(0, model.borderShortfall(0, new int[] {0, 4, 0, 0}));
        assertEquals(4, model.borderShortfall(0, new int[] {9, 0, 9, 9}), "edges with other biomes count for nothing");
    }

    @Test
    void aGroupCountsTheEdgesOfAllItsMembersTogether() {
        ClusterSizeModel model = modelOf("bc >= 5", fourBiomes());

        assertEquals(1, model.borderShortfall(0, new int[] {0, 2, 2, 0}));
        assertEquals(0, model.borderShortfall(0, new int[] {0, 2, 3, 0}));
    }

    @Test
    void anAndAddsThePartsShortfallsAndAnOrTakesTheSmallest() {
        ClusterSizeModel both = modelOf("test:b >= 3 and test:c >= 2", fourBiomes());
        ClusterSizeModel either = modelOf("test:b >= 3 or test:c >= 2", fourBiomes());

        assertEquals(3 + 1, both.borderShortfall(0, new int[] {0, 0, 1, 0}), "3 missing with B, 1 missing with C");
        assertEquals(1, either.borderShortfall(0, new int[] {0, 0, 1, 0}));
        assertEquals(0, either.borderShortfall(0, new int[] {0, 3, 0, 0}));
    }

    @Test
    void aBiomeWithoutARequirementHasNoShortfallWhateverItsBorders() {
        assertEquals(0, modelOf("test:b >= 3", fourBiomes()).borderShortfall(1, new int[] {0, 0, 0, 0}));
    }

    private static final String[] SUBJECTS = {"test:b", "test:c", "test:d", "bc", "cd"};

    private static String randomRequirement(java.util.Random random, int depth) {
        if (depth == 0 || random.nextInt(3) == 0) {
            return SUBJECTS[random.nextInt(SUBJECTS.length)] + " >= " + (1 + random.nextInt(6));
        }
        String separator = random.nextBoolean() ? " and " : " or ";
        int parts = 2 + random.nextInt(2);
        StringBuilder text = new StringBuilder();
        for (int part = 0; part < parts; part++) {
            text.append(part == 0 ? "" : separator).append("(").append(randomRequirement(random, depth - 1)).append(")");
        }
        return text.toString();
    }

    /** The search evaluates a compiled copy of each requirement; the plain reference reading must give the same shortfall for any edge counts. */
    @Test
    void theCompiledShortfallAgreesWithTheReferenceOnRandomRequirementsAndEdgeCounts() {
        java.util.Random random = new java.util.Random(7);
        for (int trial = 0; trial < 300; trial++) {
            String text = randomRequirement(random, 3);
            RawRuleTable table = RawRuleTable.from(withRequirementText(fourBiomes(), A, text));
            ClusterSizeModel model = new ClusterSizeModel(table, ATOM_WIDTH, NO_PERIMETER_RULE);
            ResolvedBorderRequirement reference = table.requirementOf(A).orElseThrow();
            for (int draw = 0; draw < 20; draw++) {
                int[] edges = {0, random.nextInt(6), random.nextInt(6), random.nextInt(6)};
                int expected = reference.shortfall(biome -> edges[table.allBiomeIds().indexOf(biome)]);

                assertEquals(expected, model.borderShortfall(0, edges), text + " with edges " + java.util.Arrays.toString(edges));
            }
        }
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

        assertTrue(failure.getMessage().contains("legal neighbor"), failure.getMessage());
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

    @Test
    void anOrWithOneAlternativeThatCanBeMetIsAccepted() {
        var catalog = withRequirementText(noLegalTriangleCatalog(500, 800), A, "test:c >= 2 or test:b >= 2");

        new ClusterSizeModel(RawRuleTable.from(catalog), ATOM_WIDTH, NO_PERIMETER_RULE);
    }

    @Test
    void anOrWhoseEveryAlternativeIsImpossibleIsRejectedSayingSo() {
        var catalog = withRequirementText(noLegalTriangleCatalog(500, 800), A, "test:c >= 2 or test:c >= 3");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(RawRuleTable.from(catalog), ATOM_WIDTH, NO_PERIMETER_RULE));

        assertTrue(failure.getMessage().contains("none of the alternatives"), failure.getMessage());
    }

    @Test
    void anAndWithOneImpossiblePartIsRejected() {
        var catalog = withRequirementText(noLegalTriangleCatalog(500, 800), A, "test:b >= 2 and test:c >= 2");

        assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(RawRuleTable.from(catalog), ATOM_WIDTH, NO_PERIMETER_RULE));
    }

    @Test
    void aGroupWithNoLegalNeighborOfTheBiomeIsRejectedNamingTheGroup() {
        var catalog = withRequirementText(withGroup(noLegalTriangleCatalog(500, 800), "far", C), A, "far >= 1");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(RawRuleTable.from(catalog), ATOM_WIDTH, NO_PERIMETER_RULE));

        assertTrue(failure.getMessage().contains("'far'"), failure.getMessage());
    }

    @Test
    void anEmptyGroupIsRejectedAsHoldingNoBiome() {
        var catalog = withRequirementText(new BiomeGraphData(noLegalTriangleCatalog(500, 800).nodes(), noLegalTriangleCatalog(500, 800).edges(),
            java.util.List.of(new BiomeGraphData.GroupData("nobody", java.util.List.of()))), A, "nobody >= 1");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ClusterSizeModel(RawRuleTable.from(catalog), ATOM_WIDTH, NO_PERIMETER_RULE));

        assertTrue(failure.getMessage().contains("holds no biome"), failure.getMessage());
    }

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
